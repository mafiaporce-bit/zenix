// Local structured memory and conservative task recovery. No external dependencies, no network.
// The same code is copied into index.html (between the "zenix-core" markers): the tests check the two stay equal.
export function vector(text){const v=Object.create(null);for(const w of String(text).toLowerCase().match(/[\p{L}\p{N}]+/gu)||[]){if(w.length<2)continue;for(const t of [w,...(w.length>4?[w.slice(0,-1)]:[])])v[t]=(v[t]||0)+1;}return v;}
export function cosine(a,b){let dot=0,aa=0,bb=0;for(const [k,v] of Object.entries(a)){aa+=v*v;dot+=v*(Object.hasOwn(b,k)?b[k]:0);}for(const v of Object.values(b))bb+=v*v;return aa&&bb?dot/Math.sqrt(aa*bb):0;}
export function memoryIndex(facts,old=[],now=Date.now()){const seen=new Map(old.map(r=>[r.text,r]));return [...new Set(facts)].map(text=>seen.get(text)||{text,created:now,category:/\b(prefer|like|love|hate|favorite|favourite)\b/i.test(text)?'preference':/\b(work|duty|job|shift)\b/i.test(text)?'work':/\b(wife|son|daughter|mother|father|mama|papa|brother|sister|friend)\b/i.test(text)?'people':'fact'});}
export function searchIndex(index,q){const v=vector(q);return index.map(r=>({...r,score:cosine(v,r.vector||vector(r.text))})).filter(r=>r.score>0).sort((a,b)=>b.score-a.score).slice(0,8);}
export function recoverTasks(tasks){return tasks.map(t=>t.status==='running'?{...t,status:'interrupted — outcome unknown'}:t);}
export function providerOrder(preferred,keys){return [...new Set([preferred,'claude','openrouter'])].filter(p=>keys[p]);}
