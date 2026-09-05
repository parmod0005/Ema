#!/usr/bin/env python3
"""VARDHANI Specialist R3 high-capacity off-device teacher architecture.

New R3 specialist design. It does NOT inherit the tiny V0.2 MTFN architecture. V0.2 remains
historical regression provenance only. This module contains no data loading, optimizer,
threshold, promotion, prospective-evidence or broker-order code.

Causal authority contract:
- lower/action input width = 200:
    NIFTY XLO74 + SENSEX XLO74 + NIFTY option state49 + 3 modality masks.
- separate 15m context input width = 48:
    NIFTY X15_23 + SENSEX X15_23 + 2 market masks.
- every action-authority output is computed without any tensor path from context15.
- context15 may produce advisory relationship/conflict outputs only.
- missing modalities are represented by zero payload plus explicit false masks upstream.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Final

import torch
import torch.nn as nn
import torch.nn.functional as F

LOWER_INPUT_WIDTH: Final[int] = 200
CONTEXT15_INPUT_WIDTH: Final[int] = 48
LOWER_WIDTH: Final[int] = 1024
LOWER_EXPANSION: Final[int] = 4
LOWER_BLOCKS: Final[int] = 45
CONTEXT_WIDTH: Final[int] = 256
CONTEXT_EXPANSION: Final[int] = 4
CONTEXT_BLOCKS: Final[int] = 12
EXPERT_COUNT: Final[int] = 8
EXPERT_HIDDEN: Final[int] = 2048
EPISODIC_DIM: Final[int] = 256
CONTEXT_EMBED_DIM: Final[int] = 128
HORIZONS: Final[tuple[int, ...]] = (1, 3, 5, 10, 15)
RETURN_QUANTILES: Final[tuple[float, ...]] = (0.10, 0.25, 0.50, 0.75, 0.90)
PATH_QUANTILES: Final[tuple[float, ...]] = (0.10, 0.50, 0.90)
LOWER_DILATION_CYCLE: Final[tuple[int, ...]] = (1, 2, 4, 8, 16, 32, 64, 128)
CONTEXT_DILATION_CYCLE: Final[tuple[int, ...]] = (1, 2, 4, 8, 16, 32)

ACTION_AUTHORITY_KEYS: Final[tuple[str, ...]] = (
    "direction_logits",
    "return_quantiles",
    "mfe_quantiles",
    "mae_quantiles",
    "target_before_stop_logits",
    "regime_logits",
    "next_regime_logits",
    "behavior_logits",
    "volatility_logits",
    "volatility_transition",
    "uncertainty",
    "option_behavior",
    "episodic_embedding",
)

CONTEXT_ADVISORY_KEYS: Final[tuple[str, ...]] = (
    "context_relation_logits",
    "context_regime_logits",
    "context_conflict_logits",
    "context_uncertainty_delta",
    "context_embedding",
)


@dataclass(frozen=True)
class R3TeacherShape:
    lower_input_width: int = LOWER_INPUT_WIDTH
    context15_input_width: int = CONTEXT15_INPUT_WIDTH
    lower_width: int = LOWER_WIDTH
    lower_blocks: int = LOWER_BLOCKS
    context_width: int = CONTEXT_WIDTH
    context_blocks: int = CONTEXT_BLOCKS
    expert_count: int = EXPERT_COUNT
    episodic_dim: int = EPISODIC_DIM


class CausalConvFFBlock(nn.Module):
    """Strict left-causal full-channel temporal convolution plus timestamp-local FFN."""

    def __init__(self, width: int, dilation: int, expansion: int = 4):
        super().__init__()
        self.dilation = int(dilation)
        self.conv = nn.Conv1d(width, width, kernel_size=3, dilation=self.dilation)
        self.norm_conv = nn.LayerNorm(width)
        self.norm_ff = nn.LayerNorm(width)
        hidden = width * expansion
        self.ff1 = nn.Linear(width, hidden)
        self.ff2 = nn.Linear(hidden, width)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        # x: [B,T,C]. Left padding only: no present/future timestamp can affect an earlier output.
        z = x.transpose(1, 2)
        z = self.conv(F.pad(z, (2 * self.dilation, 0))).transpose(1, 2)
        x = x + F.gelu(self.norm_conv(z))
        y = self.ff2(F.gelu(self.ff1(self.norm_ff(x))))
        return x + y


class CausalBackbone(nn.Module):
    def __init__(self, width: int, blocks: int, expansion: int, cycle: tuple[int, ...]):
        super().__init__()
        self.blocks = nn.ModuleList(
            [CausalConvFFBlock(width, cycle[i % len(cycle)], expansion) for i in range(blocks)]
        )
        self.final_norm = nn.LayerNorm(width)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        for block in self.blocks:
            x = block(x)
        return self.final_norm(x)

    @property
    def receptive_field_observations(self) -> int:
        # kernel=3 => +2*dilation observations per block.
        return 1 + 2 * sum(block.dilation for block in self.blocks)


class SpecialistExpert(nn.Module):
    def __init__(self, width: int = LOWER_WIDTH, hidden: int = EXPERT_HIDDEN):
        super().__init__()
        self.net = nn.Sequential(
            nn.LayerNorm(width),
            nn.Linear(width, hidden),
            nn.GELU(),
            nn.Linear(hidden, width),
        )

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return self.net(x)


class R3HighCapacityTeacher(nn.Module):
    """High-capacity causal teacher; shadow/research only until all external gates pass."""

    def __init__(self):
        super().__init__()

        self.lower_stem = nn.Sequential(
            nn.LayerNorm(LOWER_INPUT_WIDTH),
            nn.Linear(LOWER_INPUT_WIDTH, LOWER_WIDTH),
            nn.GELU(),
            nn.LayerNorm(LOWER_WIDTH),
        )
        self.lower_backbone = CausalBackbone(
            LOWER_WIDTH, LOWER_BLOCKS, LOWER_EXPANSION, LOWER_DILATION_CYCLE
        )

        self.experts = nn.ModuleList(
            [SpecialistExpert(LOWER_WIDTH, EXPERT_HIDDEN) for _ in range(EXPERT_COUNT)]
        )
        self.expert_router = nn.Linear(LOWER_WIDTH, EXPERT_COUNT)
        self.expert_fusion = nn.Sequential(
            nn.LayerNorm(LOWER_WIDTH * 2),
            nn.Linear(LOWER_WIDTH * 2, LOWER_WIDTH),
            nn.GELU(),
            nn.LayerNorm(LOWER_WIDTH),
        )
        self.action_core = nn.Sequential(
            nn.Linear(LOWER_WIDTH, LOWER_WIDTH),
            nn.GELU(),
            nn.LayerNorm(LOWER_WIDTH),
        )

        h = len(HORIZONS)
        self.direction_head = nn.Linear(LOWER_WIDTH, h * 3)
        self.return_quantile_head = nn.Linear(LOWER_WIDTH, h * len(RETURN_QUANTILES))
        self.mfe_quantile_head = nn.Linear(LOWER_WIDTH, h * len(PATH_QUANTILES))
        self.mae_quantile_head = nn.Linear(LOWER_WIDTH, h * len(PATH_QUANTILES))
        self.target_before_stop_head = nn.Linear(LOWER_WIDTH, h)
        self.regime_head = nn.Linear(LOWER_WIDTH, 10)
        self.next_regime_head = nn.Linear(LOWER_WIDTH, 10)
        self.behavior_head = nn.Linear(LOWER_WIDTH, 8)
        self.volatility_head = nn.Linear(LOWER_WIDTH, 5)
        self.volatility_transition_head = nn.Linear(LOWER_WIDTH, 1)
        self.uncertainty_head = nn.Linear(LOWER_WIDTH, h)
        self.option_behavior_head = nn.Linear(LOWER_WIDTH, 6)
        self.episodic_head = nn.Linear(LOWER_WIDTH, EPISODIC_DIM)

        # Strictly separate 15m branch. Nothing below is consumed by an action-authority head.
        self.context15_stem = nn.Sequential(
            nn.LayerNorm(CONTEXT15_INPUT_WIDTH),
            nn.Linear(CONTEXT15_INPUT_WIDTH, CONTEXT_WIDTH),
            nn.GELU(),
            nn.LayerNorm(CONTEXT_WIDTH),
        )
        self.context15_backbone = CausalBackbone(
            CONTEXT_WIDTH, CONTEXT_BLOCKS, CONTEXT_EXPANSION, CONTEXT_DILATION_CYCLE
        )
        self.context_relation_core = nn.Sequential(
            nn.LayerNorm(LOWER_WIDTH + CONTEXT_WIDTH),
            nn.Linear(LOWER_WIDTH + CONTEXT_WIDTH, 512),
            nn.GELU(),
            nn.LayerNorm(512),
            nn.Linear(512, 256),
            nn.GELU(),
            nn.LayerNorm(256),
        )
        self.context_relation_head = nn.Linear(256, 8)
        self.context_regime_head = nn.Linear(256, 10)
        self.context_conflict_head = nn.Linear(256, 3)
        self.context_uncertainty_head = nn.Linear(256, h)
        self.context_embedding_head = nn.Linear(256, CONTEXT_EMBED_DIM)

    def _expert_mix(self, h: torch.Tensor) -> tuple[torch.Tensor, torch.Tensor]:
        router = torch.softmax(self.expert_router(h), dim=-1)
        expert_stack = torch.stack([expert(h) for expert in self.experts], dim=-2)
        mixed = torch.sum(expert_stack * router.unsqueeze(-1), dim=-2)
        fused = self.expert_fusion(torch.cat([h, mixed], dim=-1))
        return fused, router

    def forward(self, lower200: torch.Tensor, context15_48: torch.Tensor) -> dict[str, torch.Tensor]:
        if lower200.shape[-1] != LOWER_INPUT_WIDTH:
            raise ValueError(f"lower input width must be {LOWER_INPUT_WIDTH}")
        if context15_48.shape[-1] != CONTEXT15_INPUT_WIDTH:
            raise ValueError(f"15m context input width must be {CONTEXT15_INPUT_WIDTH}")
        if lower200.shape[:2] != context15_48.shape[:2]:
            raise ValueError("lower and 15m context tensors must share [batch,time]")

        # Action-authority path: deliberately independent of context15_48.
        lower = self.lower_backbone(self.lower_stem(lower200))
        lower, router = self._expert_mix(lower)
        action = self.action_core(lower)
        b, t, _ = action.shape
        hcount = len(HORIZONS)

        out: dict[str, torch.Tensor] = {
            "direction_logits": self.direction_head(action).view(b, t, hcount, 3),
            "return_quantiles": self.return_quantile_head(action).view(b, t, hcount, len(RETURN_QUANTILES)),
            "mfe_quantiles": self.mfe_quantile_head(action).view(b, t, hcount, len(PATH_QUANTILES)),
            "mae_quantiles": self.mae_quantile_head(action).view(b, t, hcount, len(PATH_QUANTILES)),
            "target_before_stop_logits": self.target_before_stop_head(action),
            "regime_logits": self.regime_head(action),
            "next_regime_logits": self.next_regime_head(action),
            "behavior_logits": self.behavior_head(action),
            "volatility_logits": self.volatility_head(action),
            "volatility_transition": self.volatility_transition_head(action).squeeze(-1),
            "uncertainty": F.softplus(self.uncertainty_head(action)),
            "option_behavior": self.option_behavior_head(action),
            "episodic_embedding": F.normalize(self.episodic_head(action), dim=-1),
            "expert_router": router,
        }

        # 15m advisory relationship path. It may describe conflict/agreement, never gate the above outputs.
        ctx = self.context15_backbone(self.context15_stem(context15_48))
        relation = self.context_relation_core(torch.cat([lower, ctx], dim=-1))
        out.update({
            "context_relation_logits": self.context_relation_head(relation),
            "context_regime_logits": self.context_regime_head(relation),
            "context_conflict_logits": self.context_conflict_head(relation),
            "context_uncertainty_delta": self.context_uncertainty_head(relation),
            "context_embedding": F.normalize(self.context_embedding_head(relation), dim=-1),
        })
        return out

    @property
    def lower_receptive_field_observations(self) -> int:
        return self.lower_backbone.receptive_field_observations

    @property
    def context_receptive_field_observations(self) -> int:
        return self.context15_backbone.receptive_field_observations


def build_teacher() -> R3HighCapacityTeacher:
    return R3HighCapacityTeacher()


def parameter_count(model: nn.Module) -> int:
    return sum(int(p.numel()) for p in model.parameters())


def trainable_parameter_count(model: nn.Module) -> int:
    return sum(int(p.numel()) for p in model.parameters() if p.requires_grad)
