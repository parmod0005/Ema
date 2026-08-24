import io, os, json, time, zipfile, warnings
from pathlib import Path
from datetime import datetime, timezone
import numpy as np, pandas as pd, requests
from sklearn.pipeline import Pipeline
from sklearn.impute import SimpleImputer
from sklearn.preprocessing import StandardScaler
from sklearn.linear_model import LogisticRegression, Ridge
from sklearn.ensemble import HistGradientBoostingClassifier, HistGradientBoostingRegressor, ExtraTreesClassifier
warnings.filterwarnings('ignore')

ROOT=Path(__file__).resolve().parent; OUT=ROOT/'results'; CACHE=ROOT/'cache'; OUT.mkdir(parents=True,exist_ok=True); CACHE.mkdir(parents=True,exist_ok=True)
SYMBOL='BTCUSDT'; BASE='https://data.binance.vision/data/futures/um/monthly'; NOTIONAL=3000.; BASE_COST=.0014; COSTS=[.0006,.0010,.0014,.0020]

def months():
 y,m=2019,9; now=datetime.now(timezone.utc)
 while (y,m)<(now.year,now.month):
  yield f'{y:04d}-{m:02d}'; y,m=(y+1,1) if m==12 else (y,m+1)

def get(url,dst):
 if dst.exists() and dst.stat().st_size>200:return dst
 for a in range(4):
  try:
   r=requests.get(url,timeout=120)
   if r.status_code==404:return None
   r.raise_for_status(); dst.write_bytes(r.content); return dst
  except Exception as e:
   if a==3: print('DOWNLOAD_FAIL',url,repr(e),flush=True); return None
   time.sleep(2**a)

def read_kline(p):
 cols=['open_time','open','high','low','close','volume','close_time','quote_volume','trades','taker_base','taker_quote','ignore']
 with zipfile.ZipFile(p) as z: raw=z.read(z.namelist()[0])
 d=pd.read_csv(io.BytesIO(raw),header=None)
 if len(d) and not str(d.iloc[0,0]).replace('.','').isdigit(): d=d.iloc[1:]
 d=d.iloc[:,:12]; d.columns=cols
 for c in cols:d[c]=pd.to_numeric(d[c],errors='coerce')
 d=d.dropna(subset=['open_time','open','high','low','close']); t=d.open_time.astype('int64'); t=np.where(t>10_000_000_000_000,t//1000,t); d['ts']=pd.to_datetime(t,unit='ms',utc=True)
 return d[['ts','open','high','low','close','volume','quote_volume','trades','taker_base','taker_quote']]

def read_fund(p):
 with zipfile.ZipFile(p) as z: raw=z.read(z.namelist()[0])
 d=pd.read_csv(io.BytesIO(raw)); low={c.lower():c for c in d.columns}; tc=low.get('calc_time') or low.get('fundingtime') or low.get('funding_time') or d.columns[0]; rc=low.get('last_funding_rate') or low.get('fundingrate') or low.get('funding_rate') or d.columns[-1]
 t=pd.to_numeric(d[tc],errors='coerce'); t=np.where(t>10_000_000_000_000,t//1000,t)
 return pd.DataFrame({'ts':pd.to_datetime(t,unit='ms',utc=True),'funding':pd.to_numeric(d[rc],errors='coerce')}).dropna()

def load():
 ps=[]; fs=[]; ms=list(months()); print('MONTHS',len(ms),ms[0],ms[-1],flush=True)
 for i,ym in enumerate(ms,1):
  kn=f'{SYMBOL}-1m-{ym}.zip'; kp=get(f'{BASE}/klines/{SYMBOL}/1m/{kn}',CACHE/kn)
  if kp:
   x=read_kline(kp); ps.append(x); print('KLINE',i,len(ms),ym,len(x),flush=True)
  fn=f'{SYMBOL}-fundingRate-{ym}.zip'; fp=get(f'{BASE}/fundingRate/{SYMBOL}/{fn}',CACHE/fn)
  if fp:
   try:fs.append(read_fund(fp))
   except Exception as e:print('FUND_PARSE_FAIL',ym,e,flush=True)
 d=pd.concat(ps).drop_duplicates('ts').sort_values('ts').reset_index(drop=True); f=pd.concat(fs).drop_duplicates('ts').sort_values('ts') if fs else pd.DataFrame(columns=['ts','funding'])
 print('FULL_ROWS',len(d),'START',d.ts.min(),'END',d.ts.max(),flush=True); return d,f

def rsi(s,n=14):
 z=s.diff(); u=z.clip(lower=0).ewm(alpha=1/n,adjust=False).mean(); q=(-z.clip(upper=0)).ewm(alpha=1/n,adjust=False).mean(); return 100-100/(1+u/q.replace(0,np.nan))
def adx(d,n=14):
 pc=d.close.shift(); tr=pd.concat([(d.high-d.low).abs(),(d.high-pc).abs(),(d.low-pc).abs()],axis=1).max(axis=1); atr=tr.ewm(alpha=1/n,adjust=False).mean(); up=d.high.diff(); dn=-d.low.diff(); pdm=pd.Series(np.where((up>dn)&(up>0),up,0),index=d.index); mdm=pd.Series(np.where((dn>up)&(dn>0),dn,0),index=d.index); p=100*pdm.ewm(alpha=1/n,adjust=False).mean()/atr; m=100*mdm.ewm(alpha=1/n,adjust=False).mean()/atr; dx=100*(p-m).abs()/(p+m); return dx.ewm(alpha=1/n,adjust=False).mean(),p,m,atr

def bars15(m1,f):
 a={'open':'first','high':'max','low':'min','close':'last','volume':'sum','quote_volume':'sum','trades':'sum','taker_base':'sum','taker_quote':'sum'}; b=m1.set_index('ts').resample('15min',label='right',closed='right').agg(a).dropna().reset_index()
 if len(f):b=pd.merge_asof(b.sort_values('ts'),f.sort_values('ts'),on='ts',direction='backward')
 else:b['funding']=0
 b.funding=b.funding.fillna(0); return b

def features(b):
 d=b.copy()
 for n in [1,2,4,8,16,32,64]:d[f'ret{n}']=d.close.pct_change(n)
 for n in [9,21,50,100,200]:d[f'e{n}']=d.close.ewm(span=n,adjust=False).mean(); d[f'de{n}']=d.close/d[f'e{n}']-1; d[f'es{n}']=d[f'e{n}'].pct_change(4)
 d['e9e21']=d.e9/d.e21-1; d['e21e50']=d.e21/d.e50-1; d['rsi7']=rsi(d.close,7); d['rsi14']=rsi(d.close,14); mac=d.close.ewm(span=12,adjust=False).mean()-d.close.ewm(span=26,adjust=False).mean(); ms=mac.ewm(span=9,adjust=False).mean(); d['mac']=mac/d.close; d['mach']=(mac-ms)/d.close
 ax,p,m,atr=adx(d); d['adx']=ax; d['pdi']=p; d['mdi']=m; d['did']=(p-m)/100; d['atrp']=atr/d.close; d['rng']=(d.high-d.low)/d.open; d['body']=(d.close-d.open)/d.open; d['cloc']=(d.close-d.low)/(d.high-d.low).replace(0,np.nan)
 for n in [20,50,100]:
  vm=d.volume.rolling(n).mean(); vs=d.volume.rolling(n).std(); d[f'vz{n}']=(d.volume-vm)/vs; hi=d.high.rolling(n).max().shift(); lo=d.low.rolling(n).min().shift(); d[f'bh{n}']=d.close/hi-1; d[f'bl{n}']=d.close/lo-1
 d['tz']=(d.trades-d.trades.rolling(20).mean())/d.trades.rolling(20).std(); d['taker']=2*d.taker_base/d.volume.replace(0,np.nan)-1; d['funding']=d.funding.astype(float); d['fz']=(d.funding-d.funding.rolling(2880).mean())/d.funding.rolling(2880).std(); d['hs']=np.sin(2*np.pi*d.ts.dt.hour/24); d['hc']=np.cos(2*np.pi*d.ts.dt.hour/24); d['ds']=np.sin(2*np.pi*d.ts.dt.dayofweek/7); d['dc']=np.cos(2*np.pi*d.ts.dt.dayofweek/7); d['vreg']=d.atrp/d.atrp.rolling(2880).median(); d['trend']=(d.e9e21.abs()+d.e21e50.abs())/d.atrp.replace(0,np.nan)
 f=[c for c in d.columns if c not in ['ts','open','high','low','close','volume','quote_volume','trades','taker_base','taker_quote']]; return d.replace([np.inf,-np.inf],np.nan).reset_index(drop=True),f

def stats(rets,cost):
 n=(np.asarray(rets)-cost)*NOTIONAL
 if not len(n):return {'trades':0,'wr':0,'pf':0,'pnl':0,'dd':0,'avg':0}
 g=n[n>0].sum(); l=-n[n<0].sum(); eq=n.cumsum(); peak=np.maximum.accumulate(np.r_[0,eq])[:-1]; return {'trades':len(n),'wr':100*(n>0).mean(),'pf':float(g/l) if l else (99 if g else 0),'pnl':float(n.sum()),'dd':float(np.max(peak-eq)),'avg':float(n.mean())}
def seq(d,sig,h):
 sig=np.asarray(sig,int); o=d.open.to_numpy(float); c=d.close.to_numpy(float); out=[]; i=0
 while i<len(d)-h-2:
  s=sig[i]
  if s==0:i+=1;continue
  en=i+1; ex=en+h-1; out.append(s*(c[ex]/o[en]-1)); i=ex+1
 return np.asarray(out)
def threshold(d,p,classes,h):
 cls=np.asarray(classes); li=np.where(cls==1)[0][0]; si=np.where(cls==-1)[0][0]; best=None
 for th in np.arange(.42,.81,.02):
  sig=np.where((p[:,li]>=th)&(p[:,li]>p[:,si]),1,np.where((p[:,si]>=th)&(p[:,si]>p[:,li]),-1,0)); st=stats(seq(d,sig,h),BASE_COST)
  if st['trades']<20:continue
  sc=st['pnl']-.2*st['dd']+75*max(0,st['pf']-1)
  if best is None or sc>best[0]:best=(sc,th,st)
 return best

def cls_models():
 return {'LOGIT':lambda:Pipeline([('i',SimpleImputer(strategy='median')),('s',StandardScaler()),('m',LogisticRegression(max_iter=400,C=.2,class_weight='balanced'))]),'HGB':lambda:Pipeline([('i',SimpleImputer(strategy='median')),('m',HistGradientBoostingClassifier(max_iter=150,learning_rate=.06,max_leaf_nodes=21,l2_regularization=3,random_state=42))]),'EXTRA':lambda:Pipeline([('i',SimpleImputer(strategy='median')),('m',ExtraTreesClassifier(n_estimators=220,max_depth=10,min_samples_leaf=20,max_features=.65,class_weight='balanced',n_jobs=-1,random_state=42))])}
FOLDS=[('F1','2023-01-01','2024-01-01','2025-01-01'),('F2','2024-01-01','2025-01-01','2026-01-01'),('FINAL','2025-01-01','2026-01-01','2027-01-01')]

def classifier_grid(d,F):
 rows=[]; screen=[]
 for hn,h in [('30m',2),('1h',4),('2h',8),('4h',16),('8h',32)]:
  fut=d.close.shift(-h)/d.close-1
  for barrier in [.002,.0035,.005,.0075,.01]:
   y=pd.Series(np.where(fut>=barrier,1,np.where(fut<=-barrier,-1,0)),index=d.index); local=[]
   for fold,val0,test0,test1 in FOLDS:
    tr=(d.ts<pd.Timestamp(val0,tz='UTC'))&fut.notna(); va=(d.ts>=pd.Timestamp(val0,tz='UTC'))&(d.ts<pd.Timestamp(test0,tz='UTC'))&fut.notna(); te=(d.ts>=pd.Timestamp(test0,tz='UTC'))&(d.ts<pd.Timestamp(test1,tz='UTC'))&fut.notna()
    if tr.sum()<5000 or va.sum()<1000 or te.sum()<500:continue
    m=cls_models()['LOGIT']();m.fit(d.loc[tr,F],y[tr]);ch=threshold(d.loc[va].reset_index(drop=True),m.predict_proba(d.loc[va,F]),m.classes_,h)
    if not ch:continue
    _,th,v=ch;p=m.predict_proba(d.loc[te,F]);cl=np.asarray(m.classes_);li=np.where(cl==1)[0][0];si=np.where(cl==-1)[0][0];sig=np.where((p[:,li]>=th)&(p[:,li]>p[:,si]),1,np.where((p[:,si]>=th)&(p[:,si]>p[:,li]),-1,0)); rr=seq(d.loc[te].reset_index(drop=True),sig,h); t=stats(rr,BASE_COST); row={'family':'CLASS','model':'LOGIT','horizon':hn,'h':h,'barrier':barrier,'fold':fold,'threshold':th,**{'val_'+k:z for k,z in v.items()},**{'test_'+k:z for k,z in t.items()}}
    for co in COSTS: st=stats(rr,co);row[f'pf_{int(co*10000)}bps']=st['pf'];row[f'pnl_{int(co*10000)}bps']=st['pnl']
    rows.append(row);local.append(row)
   if local:screen.append({'horizon':hn,'h':h,'barrier':barrier,'good':sum(x['test_pnl']>0 for x in local),'pnl':sum(x['test_pnl'] for x in local),'pf':np.mean([x['test_pf'] for x in local]),'trades':sum(x['test_trades'] for x in local)})
 sc=pd.DataFrame(screen).sort_values(['good','pnl','pf'],ascending=False);sc.to_csv(OUT/'screen.csv',index=False)
 for cfg in sc.head(8).to_dict('records'):
  h=int(cfg['h']);barrier=float(cfg['barrier']);hn=cfg['horizon'];fut=d.close.shift(-h)/d.close-1;y=pd.Series(np.where(fut>=barrier,1,np.where(fut<=-barrier,-1,0)),index=d.index)
  for mn in ['HGB','EXTRA']:
   for fold,val0,test0,test1 in FOLDS:
    tr=(d.ts<pd.Timestamp(val0,tz='UTC'))&fut.notna();va=(d.ts>=pd.Timestamp(val0,tz='UTC'))&(d.ts<pd.Timestamp(test0,tz='UTC'))&fut.notna();te=(d.ts>=pd.Timestamp(test0,tz='UTC'))&(d.ts<pd.Timestamp(test1,tz='UTC'))&fut.notna();m=cls_models()[mn]();m.fit(d.loc[tr,F],y[tr]);ch=threshold(d.loc[va].reset_index(drop=True),m.predict_proba(d.loc[va,F]),m.classes_,h)
    if not ch:continue
    _,th,v=ch;p=m.predict_proba(d.loc[te,F]);cl=np.asarray(m.classes_);li=np.where(cl==1)[0][0];si=np.where(cl==-1)[0][0];sig=np.where((p[:,li]>=th)&(p[:,li]>p[:,si]),1,np.where((p[:,si]>=th)&(p[:,si]>p[:,li]),-1,0));rr=seq(d.loc[te].reset_index(drop=True),sig,h);t=stats(rr,BASE_COST);row={'family':'CLASS','model':mn,'horizon':hn,'h':h,'barrier':barrier,'fold':fold,'threshold':th,**{'val_'+k:z for k,z in v.items()},**{'test_'+k:z for k,z in t.items()}}
    for co in COSTS:st=stats(rr,co);row[f'pf_{int(co*10000)}bps']=st['pf'];row[f'pnl_{int(co*10000)}bps']=st['pnl']
    rows.append(row)
 return pd.DataFrame(rows)

def regression_grid(d,F):
 rows=[]
 for hn,h in [('1h',4),('2h',8),('4h',16),('8h',32)]:
  y=(d.close.shift(-h)/d.close-1).clip(-.08,.08)
  for mn,maker in [('RIDGE',lambda:Pipeline([('i',SimpleImputer(strategy='median')),('s',StandardScaler()),('m',Ridge(alpha=20))])),('HGBR',lambda:Pipeline([('i',SimpleImputer(strategy='median')),('m',HistGradientBoostingRegressor(max_iter=150,learning_rate=.05,max_leaf_nodes=21,l2_regularization=5,random_state=42))]))]:
   for fold,val0,test0,test1 in FOLDS:
    tr=(d.ts<pd.Timestamp(val0,tz='UTC'))&y.notna();va=(d.ts>=pd.Timestamp(val0,tz='UTC'))&(d.ts<pd.Timestamp(test0,tz='UTC'))&y.notna();te=(d.ts>=pd.Timestamp(test0,tz='UTC'))&(d.ts<pd.Timestamp(test1,tz='UTC'))&y.notna();m=maker();m.fit(d.loc[tr,F],y[tr]);pv=m.predict(d.loc[va,F]);best=None
    for edge in [.0015,.002,.0025,.003,.004,.005,.0075,.01]:
     st=stats(seq(d.loc[va].reset_index(drop=True),np.where(pv>=edge,1,np.where(pv<=-edge,-1,0)),h),BASE_COST)
     if st['trades']<20:continue
     score=st['pnl']-.2*st['dd']+75*max(0,st['pf']-1)
     if best is None or score>best[0]:best=(score,edge,st)
    if not best:continue
    _,edge,v=best;pt=m.predict(d.loc[te,F]);rr=seq(d.loc[te].reset_index(drop=True),np.where(pt>=edge,1,np.where(pt<=-edge,-1,0)),h);t=stats(rr,BASE_COST);row={'family':'REG','model':mn,'horizon':hn,'h':h,'fold':fold,'edge':edge,**{'val_'+k:z for k,z in v.items()},**{'test_'+k:z for k,z in t.items()}}
    for co in COSTS:st=stats(rr,co);row[f'pf_{int(co*10000)}bps']=st['pf'];row[f'pnl_{int(co*10000)}bps']=st['pnl']
    rows.append(row)
 return pd.DataFrame(rows)

def meta_grid(d,F):
 h=8;long=(d.e9>d.e21)&(d.e21>d.e50)&(d.adx>18)&(d.taker>0);short=(d.e9<d.e21)&(d.e21<d.e50)&(d.adx>18)&(d.taker<0);boL=(d.bh20>0)&(d.vz20>.3);boS=(d.bl20<0)&(d.vz20>.3);side=np.where(long|boL,1,np.where(short|boS,-1,0));fut=d.close.shift(-h)/d.close-1;cand=(side!=0)&fut.notna();M=d.loc[cand].copy();M['side']=side[cand];M['y']=(side[cand]*fut[cand]>BASE_COST).astype(int);MF=F+['side'];rows=[]
 for mn in ['LOGIT','HGB']:
  for fold,val0,test0,test1 in FOLDS:
   tr=M.ts<pd.Timestamp(val0,tz='UTC');va=(M.ts>=pd.Timestamp(val0,tz='UTC'))&(M.ts<pd.Timestamp(test0,tz='UTC'));te=(M.ts>=pd.Timestamp(test0,tz='UTC'))&(M.ts<pd.Timestamp(test1,tz='UTC'))
   if mn=='LOGIT':m=Pipeline([('i',SimpleImputer(strategy='median')),('s',StandardScaler()),('m',LogisticRegression(max_iter=400,C=.2,class_weight='balanced'))])
   else:m=Pipeline([('i',SimpleImputer(strategy='median')),('m',HistGradientBoostingClassifier(max_iter=150,learning_rate=.05,max_leaf_nodes=21,l2_regularization=4,random_state=42))])
   m.fit(M.loc[tr,MF],M.loc[tr,'y']);pv=m.predict_proba(M.loc[va,MF])[:,1];best=None
   for th in np.arange(.50,.81,.025):
    mv=M.loc[va].reset_index(drop=True);st=stats(seq(mv,np.where(pv>=th,mv.side,0),h),BASE_COST)
    if st['trades']<15:continue
    score=st['pnl']-.2*st['dd']+75*max(0,st['pf']-1)
    if best is None or score>best[0]:best=(score,th,st)
   if not best:continue
   _,th,v=best;mt=M.loc[te].reset_index(drop=True);pt=m.predict_proba(M.loc[te,MF])[:,1];rr=seq(mt,np.where(pt>=th,mt.side,0),h);t=stats(rr,BASE_COST);row={'family':'META','model':mn,'horizon':'2h','h':h,'fold':fold,'threshold':th,**{'val_'+k:z for k,z in v.items()},**{'test_'+k:z for k,z in t.items()}}
   for co in COSTS:st=stats(rr,co);row[f'pf_{int(co*10000)}bps']=st['pf'];row[f'pnl_{int(co*10000)}bps']=st['pnl']
   rows.append(row)
 return pd.DataFrame(rows)

def rank(R):
 keys=['family','model','horizon']; rows=[]
 for key,g in R.groupby(keys):
  x=dict(zip(keys,key));x.update({'folds':len(g),'positive_folds':int((g.test_pnl>0).sum()),'pf_gt1_folds':int((g.test_pf>1).sum()),'mean_pf':g.test_pf.mean(),'sum_pnl':g.test_pnl.sum(),'trades':g.test_trades.sum(),'worst_pnl':g.test_pnl.min(),'max_dd':g.test_dd.max(),'mean_pf_20bps':g['pf_20bps'].mean(),'sum_pnl_20bps':g['pnl_20bps'].sum()});rows.append(x)
 return pd.DataFrame(rows).sort_values(['positive_folds','sum_pnl','mean_pf'],ascending=False)

def main():
 m1,f=load(); b=bars15(m1,f);d,F=features(b); (OUT/'dataset_summary.json').write_text(json.dumps({'rows_1m':len(m1),'rows_15m':len(d),'start':str(m1.ts.min()),'end':str(m1.ts.max()),'features':len(F)},indent=2)); print('15M',len(d),'FEATURES',len(F),flush=True)
 C=classifier_grid(d,F);C.to_csv(OUT/'class_walk.csv',index=False);R=regression_grid(d,F);R.to_csv(OUT/'reg_walk.csv',index=False);M=meta_grid(d,F);M.to_csv(OUT/'meta_walk.csv',index=False);A=pd.concat([C,R,M],ignore_index=True,sort=False);A.to_csv(OUT/'all_results.csv',index=False);Q=rank(A);Q.to_csv(OUT/'ranking.csv',index=False);S=Q[(Q.folds>=2)&(Q.positive_folds>=2)&(Q.pf_gt1_folds>=2)&(Q.trades>=50)&(Q.sum_pnl>0)&(Q.mean_pf_20bps>=1)&(Q.sum_pnl_20bps>0)].copy();S.to_csv(OUT/'STRICT_SURVIVORS.csv',index=False)
 rep=['# BTCUSDT USD-M Futures Full-History AI Research',f'\n1m rows: {len(m1):,}; range: {m1.ts.min()} — {m1.ts.max()}; 15m rows: {len(d):,}; features: {len(F)}.','\nWalk-forward test folds: 2024, 2025, 2026. Base cost 14 bps; stress 6/10/14/20 bps. One position at a time, signal on completed bar, entry next bar open.','\n## STRICT SURVIVORS\n',S.to_markdown(index=False) if len(S) else 'NONE — no model met every promotion criterion.','\n## TOP RANKING\n',Q.head(30).to_markdown(index=False)];(OUT/'REPORT.md').write_text('\n'.join(rep));print('STRICT_SURVIVORS',len(S),flush=True);print(S.head(20).to_string(index=False) if len(S) else 'NONE',flush=True)
if __name__=='__main__':main()
