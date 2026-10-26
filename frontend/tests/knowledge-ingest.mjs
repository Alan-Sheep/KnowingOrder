import test from 'node:test';
import assert from 'node:assert/strict';
import { ingestJob, ingestJobs } from '../src/knowledge-ingest.ts';
import { createEnterpriseClient } from '../src/enterprise.ts';

const id='00000000-0000-4000-8000-000000000001';
const job={ id,documentId:'11',versionId:'12',expectedRevision:1,action:'PARSE',status:'QUEUED',attempts:0,
  createdMs:1800000000000,deadlineMs:1800000030000,finishedMs:null,failureCode:null,message:'已受理' };
const ok=data=>Response.json({code:0,data});
test('入库契约拒绝空值、错误编号、状态、时间、尝试次数与失败分类',()=>{
  assert.deepEqual(ingestJob(job),job);
  for(const v of [null,[],{...job,id:'bad'},{...job,documentId:11},{...job,action:'DELETE'},
    {...job,status:'OK'},{...job,attempts:3},{...job,deadlineMs:job.createdMs+30001},
    {...job,failureCode:'SQL_SECRET'},{...job,status:'SUCCEEDED'}, {...job,finishedMs:'-1'},
    {...job,status:'CANCELLED',finishedMs:'1'}, {...job,status:'RUNNING'}, {...job,attempts:1},
    {...job,status:'FAILED',finishedMs:String(job.createdMs)},
    {...job,createdMs:Number.MAX_SAFE_INTEGER,deadlineMs:Number.MAX_SAFE_INTEGER+30000}]) assert.throws(()=>ingestJob(v));
  assert.throws(()=>ingestJobs(Array(11).fill(job)));
});
test('任务API带CSRF与完整参数，刷新读取不重新提交',async()=>{
  const calls=[];
  const api=createEnterpriseClient(async(path,init)=>{
    calls.push({path,init}); if(path.endsWith('/csrf'))return ok({headerName:'X-CSRF-TOKEN',token:'synthetic-only'});
    if(init.method==='POST')assert.equal(init.headers['X-CSRF-TOKEN'],'synthetic-only');
    return ok(path.endsWith('/ingest-jobs')&&init.method!=='POST'?[job]:job);
  });
  await api.submitIngest('1','2','11','12',id,1,'PARSE');
  await api.readIngest('1','2','11','12',id); await api.ingestHistory('1','2','11','12');
  await api.cancelIngest('1','2','11','12',id);
  const writes=calls.filter(v=>v.init.method==='POST'); assert.equal(writes.length,2);
  assert.deepEqual(JSON.parse(writes[0].init.body),{requestId:id,expectedRevision:1,action:'PARSE'});
  assert.ok(writes[1].path.endsWith(id+'/cancel'));
});
test('入库写响应丢失保留未知状态，不自动重试，原UUID可读取核对',async()=>{
  let writes=0;
  const api=createEnterpriseClient(async(path,init)=>{
    if(path.endsWith('/csrf'))return ok({headerName:'X-CSRF-TOKEN',token:'synthetic-only'});
    if(init.method==='POST'){writes++;throw new TypeError('合成断网');}return ok(job);
  });
  await assert.rejects(api.submitIngest('1','2','11','12',id,1,'PARSE'),e=>e.uncertain);
  assert.equal(writes,1); assert.equal((await api.readIngest('1','2','11','12',id)).id,id);assert.equal(writes,1);
});
test('入库非法参数在网络前拒绝，第三方异常正文不显示',async()=>{
  let calls=0;const api=createEnterpriseClient(async()=>{calls++;return ok(null);});
  await assert.rejects(api.submitIngest('1','2','11','12',id,0,'PARSE'));
  await assert.rejects(api.submitIngest('1','2','11','12',id,1,'DELETE'));
  await assert.rejects(api.readIngest('1','2','11','12','bad'));assert.equal(calls,0);
  const bad=createEnterpriseClient(async()=>Response.json({code:500,message:'PASSWORD_SECRET'}));
  await assert.rejects(bad.readIngest('1','2','11','12',id),e=>!e.message.includes('SECRET'));
});
