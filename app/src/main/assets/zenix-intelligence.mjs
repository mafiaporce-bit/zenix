// Pure helpers: no network, credentials or side effects.
export function routeBrain(text,provider,current,unsupported={}){
 const complex=/\b(analy[sz]e|compare|research|debug|architecture|explain in detail|plan|write code|multiple steps|review)\b/i.test(text)||text.length>450;
 if(provider==='openrouter'){
  const model=complex?'x-ai/grok-4.7':'x-ai/grok-4.3';return unsupported[model]?current:model;
 }
 return complex?'claude-sonnet-5-5':'claude-haiku-5-5';
}
const groups=[['car','vehicle','automobile','rav4'],['job','work','duty','shift','employment'],['wife','spouse','partner'],['kid','child','son','daughter','children'],['app','application','software','project'],['cost','price','budget','expense','payment'],['doctor','physician','appointment','clinic']];
function terms(text){const out=new Set(String(text).toLowerCase().match(/[\p{L}\p{N}]+/gu)||[]);for(const g of groups)if(g.some(w=>out.has(w)))out.add(g[0]);return out;}
export function rankMemory(query,record){const a=terms(query),b=terms(record),stop=new Set(['the','a','an','my','is','are','of','to','and','in','on','for','what','who','do','you','remember','about','me']);let n=0;for(const w of a)if(w.length>1&&!stop.has(w)&&b.has(w))n++;return n;}
// A rule waits for a CHANGE: "when the internet is back" only fires after the internet was seen gone (seen), so a rule added while online doesn't fire at once.
export function conditionReady(rule,online){return !rule.fired&&rule.seen!==false&&((rule.kind==='online'&&online===true)||(rule.kind==='offline'&&online===false));}
