import asyncio,json,os,pathlib,sys,uuid,urllib.request,subprocess,importlib.util,time
from hermes_cli.plugins import discover_plugins
discover_plugins()
from gateway.config import PlatformConfig
from gateway.run_inbound import GatewayInboundMixin
root=pathlib.Path(__file__).parent;token=(root/'client.token').read_text();base='http://127.0.0.1:18144'
spec=importlib.util.spec_from_file_location('p14_acceptance_adapter','/home/ubuntu/work/P14-inbox-protocol/hermes-plugin/p14/adapter.py');mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(mod)
Adapter=mod.P14Adapter
async def main():
 adapter=Adapter(PlatformConfig(enabled=True,extra={'server_url':base,'token':token}))
 sessions={};calls=[];errors=[];streamed=[]
 async def handler(event):
  cid=event.source.chat_id
  prompt=GatewayInboundMixin._prepend_inbound_reply_context(event,event.source,event.text)
  cmd=['/home/ubuntu/.hermes/hermes-agent/.hermes/bin/hermes','chat','--format','stream-json','--query-file','-','--source','p14-live-platform','--in',str(root),'-t','p14_acceptance_no_tools','--ignore-rules','--max-turns','1','--run-budget','90','--reasoning','low']
  cmd+=['--resume',sessions[cid]] if cid in sessions else ['--continue','p14-platform-e2e-'+cid,'--create-if-missing']
  p=await asyncio.create_subprocess_exec(*cmd,stdin=asyncio.subprocess.PIPE,stdout=asyncio.subprocess.PIPE,stderr=asyncio.subprocess.PIPE)
  p.stdin.write(prompt.encode());await p.stdin.drain();p.stdin.close()
  stderr=asyncio.create_task(p.stderr.read());result=None;draft='';raw=[]
  while line:=await p.stdout.readline():
   raw.append(line.decode())
   try:frame=json.loads(line)
   except Exception:continue
   if frame.get('type')=='text':
    draft+=frame.get('text','');r=await adapter.send_draft(cid,1000+len(calls),draft);assert r.success
    streamed.append({'conversation_id':cid,'chars':len(draft)})
   if frame.get('type')=='result':result=frame
  await p.wait();err=await stderr
  (root/f'platform-turn-{len(calls)+1}.jsonl').write_text(''.join(raw))
  if p.returncode or not result or result.get('exit_code')!=0:
   (root/'platform-error.log').write_bytes(err);raise RuntimeError('Real Hermes platform turn failed')
  sid=result['session_id'];assert cid not in sessions or sessions[cid]==sid;sessions[cid]=sid
  calls.append({'conversation_id':cid,'message_id':event.message_id,'session_id':sid,'text':result['text'],'reply_to_text':event.reply_to_text,'reply_to_message_id':event.reply_to_message_id})
  (root/'native-service-journal.json').write_text(json.dumps({'calls':calls,'streamed_updates':len(streamed)},ensure_ascii=False,indent=2))
  return result['text']
 adapter.set_message_handler(handler)
 assert await adapter.connect()
 def api(path,data=None):
  r=urllib.request.Request(base+'/v1/'+path,data=json.dumps(data,ensure_ascii=False).encode() if data is not None else None,headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'})
  with urllib.request.urlopen(r,timeout=20) as f:return json.load(f)
 try:
  if '--service' in sys.argv:
   print('Isolated native acceptance adapter ready',flush=True)
   deadline=time.monotonic()+3300
   while time.monotonic()<deadline and not (root/'stop-native-service').exists():await asyncio.sleep(1)
   return
  e,c,m=[str(uuid.uuid4()) for _ in range(3)];original='独立平台验收：暗号 OLD-OBSOLETE。';code='PLUM-'+uuid.uuid4().hex[:8];body='独立平台验收：当前暗号 '+code+'。'
  await asyncio.to_thread(api,'entries/capture',{'client_operation_id':str(uuid.uuid4()),'entry_id':e,'conversation_id':c,'message_id':m,'text':original,'attachment_ids':[]})
  await asyncio.sleep(1);assert not calls
  await asyncio.to_thread(api,f'conversations/{c}/messages/{m}/edit',{'client_operation_id':str(uuid.uuid4()),'base_revision':1,'text':body})
  for index,question in enumerate(['只按当前引用的记录回答暗号。请仅输出暗号，不调用工具。','仅根据刚才的对话，再说一次暗号，不读取文件，不调用工具。']):
   mid=str(uuid.uuid4());payload={'client_operation_id':str(uuid.uuid4()),'message_id':mid,'text':question,'attachment_ids':[]}
   if index==0:payload.update(reply_to_message_id=m,quote={'text':body,'source_revision':2})
   await asyncio.to_thread(api,f'conversations/{c}/messages',payload)
   assert (await asyncio.to_thread(api,f'conversations/{c}/messages',payload))['deduplicated']
   deadline=time.monotonic()+120
   while time.monotonic()<deadline:
    result=await asyncio.to_thread(api,'hermes/inbound/next')
    if len(calls)>index and result['event'] is None:break
    await asyncio.sleep(.25)
   else:raise RuntimeError('P14 adapter final/ACK timeout')
   assert code in calls[index]['text'];print(json.dumps({'platform_turn':index+1,'real_reply':True,'context_pass':True}),flush=True)
  assert len(sessions)==1 and len(calls)==2 and len({v['message_id'] for v in calls})==2
  assert len(streamed)>0
  report={'isolated_server':True,'actual_p14_platform_adapter':True,'actual_baseplatform_message_dispatch':True,'hermes_backend':'stock CLI, existing provider config, no tools','hermes_invoked':True,'production_untouched':True,'capture_without_ai':True,'same_conversation':True,'same_hermes_session':True,'streamed_updates':len(streamed),'turns':calls,'android_activity_live_tested':False}
  (root/'platform-result.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
  print(json.dumps({'platform_e2e_pass':True,'streamed_updates':len(streamed),'same_session':True}),flush=True)
 finally:await adapter.disconnect()
asyncio.run(main())
