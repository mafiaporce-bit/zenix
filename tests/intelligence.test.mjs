import test from 'node:test';import assert from 'node:assert/strict';import {routeBrain,rankMemory,conditionReady} from '../app/src/main/assets/zenix-intelligence.mjs';
test('routes routine and complex requests',()=>{assert.equal(routeBrain('hello','claude'),'claude-haiku-5-5');assert.equal(routeBrain('analyze this plan','claude'),'claude-sonnet-5-5');assert.equal(routeBrain('research','openrouter'),'x-ai/grok-4.7');});
test('respects known unsupported route',()=>assert.equal(routeBrain('research','openrouter','fallback',{'x-ai/grok-4.7':true}),'fallback'));
test('synonym ranking',()=>{assert.ok(rankMemory('my spouse','my wife')>rankMemory('my spouse','my car'));assert.equal(rankMemory('the','the'),0);});
test('conditions are one-shot',()=>{assert.equal(conditionReady({kind:'online'},true),true);assert.equal(conditionReady({kind:'offline'},true),false);assert.equal(conditionReady({kind:'online',fired:true},true),false);});

import {vector,cosine,memoryIndex,searchIndex,recoverTasks,providerOrder} from '../app/src/main/assets/zenix-core.mjs';
test('provider order uses configured providers only',()=>{assert.deepEqual(providerOrder('claude',{claude:false,openrouter:true}),['openrouter']);});
test('local vector index matches and deletes removed facts',()=>{const i=memoryIndex(['I prefer dark green','My car is blue']);assert.equal(searchIndex(i,'prefer green')[0].text,'I prefer dark green');assert.equal(memoryIndex(['My car is blue'],i).length,1);});
test('interrupted tasks are never automatically replayed',()=>{assert.match(recoverTasks([{status:'running'}])[0].status,/unknown/);});
test('vector similarity tolerates prototype-like terms',()=>{assert.equal(cosine(vector('constructor'),vector('constructor')),1);});
test('a rule added while online waits until the internet was seen gone',()=>{assert.equal(conditionReady({kind:'online',seen:false},true),false);assert.equal(conditionReady({kind:'online',seen:true},true),true);assert.equal(conditionReady({kind:'offline',seen:true},false),true);});
test('memory index keeps the saved day and a category',()=>{const i=memoryIndex(['I prefer tea','My wife is Ana'],[],5);assert.equal(i[0].created,5);assert.equal(i[0].category,'preference');assert.equal(i[1].category,'people');const j=memoryIndex(['My wife is Ana','New fact'],i,9);assert.equal(j[0].created,5);assert.equal(j[1].created,9);});
test('search works on an index saved without vectors',()=>{assert.equal(searchIndex([{text:'My car is blue'},{text:'I like rice'}],'blue car')[0].text,'My car is blue');});
test('Opus picked: hard questions go to Opus, quick ones stay on Haiku',()=>{assert.equal(routeBrain('analyze this plan','claude','claude-opus-5-5'),'claude-opus-5-5');assert.equal(routeBrain('hello','claude','claude-opus-5-5'),'claude-haiku-5-5');assert.equal(routeBrain('analyze this plan','claude','claude-sonnet-5-5'),'claude-sonnet-5-5');});
