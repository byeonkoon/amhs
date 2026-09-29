import React from 'react';
import {createRoot} from 'react-dom/client';
import './style.css';
type Node={id:number;code:string;x:number;y:number};
type Port={id:number;code:string;accessVehicleType:string;linkedNodeId:number};
type MapData={revisionId:number;nodes:Node[];edges:{id:number;fromNodeId:number;toNodeId:number;enabled:boolean}[];ports:Port[]};
type Vehicle={id:number;code:string;vType:string;currentNodeId:number;state:string;positionSource:string};
async function get<T>(url:string, signal:AbortSignal):Promise<T>{const r=await fetch(url,{signal});if(!r.ok)throw Error(url+' HTTP '+r.status);return r.json();}
function App(){
 const [map,setMap]=React.useState<MapData|null>(null),[vehicles,setVehicles]=React.useState<Vehicle[]>([]),[error,setError]=React.useState(''),[selected,setSelected]=React.useState<Vehicle|null>(null),[version,setVersion]=React.useState(0);
 React.useEffect(()=>{const controller=new AbortController();setError('');setMap(null);setVehicles([]);setSelected(null);
 Promise.all([get<MapData>('/api/v1/sites/1/maps/1',controller.signal),get<Vehicle[]>('/api/v1/sites/1/vehicles',controller.signal),get<{status:string}>('/actuator/health',controller.signal)])
 .then(([m,v,h])=>{if(h.status!=='UP')throw Error('서버 상태: '+h.status);setMap(m);setVehicles(v);})
 .catch(e=>{if(e.name!=='AbortError')setError(e.message);});return()=>controller.abort();},[version]);
 const point=(id:number)=>map?.nodes.find(n=>n.id===id);
 return <div className="shell"><aside><div className="brand">AMHS<span>OPERATIONS</span></div><p className="tagline">Automated Material<br/>Handling System</p><div className="section">WORKSPACE / DEMO FAB</div><nav><div className="active">◈　지도·차량<span>Map / Fleet</span></div><div>설정·지도 기반<span>M1 · 구현 중</span></div></nav><footer><i className={map?'online':''}/>{map?'서버 · DB 연결됨':'서버 연결 확인 중'}<small>LOCAL DEVELOPMENT · v0.1.0</small></footer></aside>
 <main><header><span>AMHS / 운영 / 지도</span><span className="env">가상 데이터 · 개발 환경</span></header><div className="heading"><div><p className="eyebrow">FLEET CONTROL PLATFORM</p><h1>지도·차량 현황</h1><p>노드 연결과 포트 접근 차종을 기준으로 OHT·AGV 동선을 분리합니다.</p></div><button onClick={()=>setVersion(v=>v+1)}>새로고침 ↻</button></div>
 {error?<div role="alert" className="error"><b>서버 데이터를 불러오지 못했습니다.</b><p>{error}</p><p>Spring 서버와 SQL Server 연결을 확인해 주세요.</p></div>:!map?<p role="status">서버 지도 데이터를 불러오는 중…</p>:<>
 <div className="metrics">{[['지도 버전','REV '+map.revisionId],['연결 노드',map.nodes.length],['등록 차량',vehicles.length],['포트',map.ports.length]].map(([label,value])=><div className="metric" key={label}><span>{label}</span><strong>{value}</strong></div>)}</div>
 <div className="grid"><section className="card"><div className="cardtitle"><h2>가상 FAB 지도</h2><span>● OHT　<span className="agvtext">● AGV</span></span></div><p className="hint">차량 표시는 초기 설정 위치입니다. 실시간 수신 상태가 아닙니다.</p>
 <svg viewBox="0 0 8500 5300" role="img" aria-label="서로 연결되지 않은 OHT와 AGV 지도"><defs><pattern id="grid" width="250" height="250" patternUnits="userSpaceOnUse"><circle cx="0" cy="0" r="9" fill="#d9e2ec"/></pattern></defs><rect width="8500" height="5300" fill="url(#grid)"/>
 <text x="500" y="400" className="maplabel">OHT · 천장 레일</text><text x="500" y="3400" className="maplabel agvtext">AGV · 바닥 주행로</text>
 {map.edges.map(e=>{const a=point(e.fromNodeId),b=point(e.toNodeId);return a&&b?<line key={e.id} x1={a.x} y1={a.y} x2={b.x} y2={b.y} stroke={a.code.startsWith('OHT')?'#2864cf':'#944ba1'} strokeWidth="38" strokeDasharray={a.code.startsWith('OHT')?'':'100 60'}/>:null;})}
 {map.nodes.map(n=><g key={n.id}><circle cx={n.x} cy={n.y} r="65" fill="#fff" stroke="#456" strokeWidth="20"/><text x={n.x} y={n.y+300} textAnchor="middle">{n.code}</text></g>)}
 {map.ports.map(p=>{const n=point(p.linkedNodeId);return n?<g key={p.id}><rect x={n.x-390} y={n.y+500} width="780" height="330" rx="65" fill="#edf2f8"/><text x={n.x} y={n.y+730} textAnchor="middle">{p.code}</text></g>:null;})}
 {vehicles.map(v=>{const n=point(v.currentNodeId);return n?<g key={v.id} role="button" tabIndex={0} aria-label={v.code+' 상세'} onClick={()=>setSelected(v)} onKeyDown={e=>{if(e.key==='Enter'||e.key===' '){e.preventDefault();setSelected(v);}}}><rect x={n.x-300} y={n.y-530} width="600" height="330" rx="65" fill={v.vType==='OHT'?'#2864cf':'#944ba1'}/><text x={n.x} y={n.y-300} fill="white" textAnchor="middle">{v.code}</text></g>:null;})}</svg>
 <div className="mapfoot">좌표 mm · 방향별 edge 저장 · 네트워크 간 연결 없음</div></section>
 <section className="card fleet"><h2>등록 차량</h2>{vehicles.map(v=><button className={'vehicle '+(selected?.id===v.id?'selected':'')} key={v.id} onClick={()=>setSelected(v)}><b>{v.code}</b><span>{v.vType} / 미연결</span></button>)}<div className="detail"><h3>{selected?.code??'차량을 선택하세요'}</h3>{selected?<><p>초기 노드 <b>{point(selected.currentNodeId)?.code}</b></p><p>상태 <b>미연결</b></p><p>배차 <b>비활성</b></p></>:<p>지도 또는 목록에서 상세 정보를 확인하세요.</p>}</div><p className="hint">현재 단계는 설정 조회와 지도 표시입니다. 반송 제어 및 장비 어댑터는 아직 연결되지 않았습니다.</p></section></div>
 <section className="card ports"><h2>포트 접근 조건</h2><table><thead><tr><th>포트</th><th>접근 차종</th><th>연결 노드</th></tr></thead><tbody>{map.ports.map(p=><tr key={p.id}><td>{p.code}</td><td>{p.accessVehicleType}</td><td>{point(p.linkedNodeId)?.code}</td></tr>)}</tbody></table></section></>}
 <div className="bottom">AMHS Operations <span>구현 단계 M1 · 합성 데이터 · 실장비 미연동</span></div></main></div>;
}
createRoot(document.getElementById('root')!).render(<React.StrictMode><App/></React.StrictMode>);
