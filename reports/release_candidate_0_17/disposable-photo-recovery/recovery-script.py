import argparse,json,re,sys,traceback
from pathlib import Path
sys.path.insert(0,str(Path('tools').resolve()))
from run_android_017_capture_smoke import CaptureSmoke,MONO_CODE
from run_android_billing_smoke import FREE
old_path=Path('reports/release_candidate_0_17/play-capture-attempt1-short-review/evaluation.json')
old=json.loads(old_path.read_text())
args=argparse.Namespace(adb='/Users/songyuanjin/Library/Android/sdk/platform-tools/adb',serial='emulator-5580',package='com.ycolor.team.phytoy.camera.android.gpapp',activity='com.phytoy.sample.MainActivity',channel='play',expected_apk_sha256='4f92e2b73abeeb25449ec71ed38cfd9e93f8ef464f1168a6f399490468dbdd1b',allow_disposable_photo=True,output=Path('reports/release_candidate_0_17/disposable-photo-recovery'))
s=CaptureSmoke(args);s.report['scope']='Delete only the uniquely verified disposable photo from the retained capture attempt; no new capture'
style=permissions=locale=font=None
try:
 assert old['package']==args.package and old['serial']==args.serial
 assert old['installed_apk_sha256']==args.expected_apk_sha256
 assert old['capture_taps']==1 and old['delete_confirm_taps']==0 and old['unique_mono_identity_verified']
 assert s.adb('get-serialno').strip()==args.serial and s.adb('shell','getprop','ro.kernel.qemu').strip()=='1'
 paths=[x[8:] for x in s.adb('shell','pm','path',args.package).splitlines() if x.startswith('package:')]
 assert len(paths)==1 and s.shell('sha256sum',paths[0]).split()[0]==args.expected_apk_sha256
 s.report['installed_apk_sha256']=args.expected_apk_sha256
 s.initial_ids=tuple(next(x['ids'] for x in old['media_history'] if x['stage']=='initial'))
 s.new_id=old['new_photo_id'];assert s.new_id not in s.initial_ids
 sizes=re.findall(r'(?:Physical|Override) size:\s*(\d+)x(\d+)',s.adb('shell','wm','size'))
 density=re.findall(r'(?:Physical|Override) density:\s*(\d+)',s.adb('shell','wm','density'))
 s.width,s.height=map(int,sizes[-1]);s.density=int(density[-1])/160
 permissions=s.permissions();locale=s.adb('shell','getprop','persist.sys.locale').strip();font=s.adb('shell','settings','get','system','font_scale').strip()
 assert s.media('before-recovery')==tuple(sorted(s.initial_ids+(s.new_id,)))
 for expected in old['initial_photo_exif']:
  current=s.photo_exif(expected['media_id'],'initial-recheck');assert current['jpeg_sha256']==expected['jpeg_sha256'] and current['style_code']!=MONO_CODE
 new=s.photo_exif(s.new_id,'new-recheck');assert new['jpeg_sha256']==old['new_photo_exif']['jpeg_sha256'] and new['style_code']==MONO_CODE
 assert old['saved_log_evidence']['media_id']==s.new_id and old['saved_log_evidence']['style_code']==MONO_CODE
 s.report.update(new_photo_exif=new,unique_mono_identity_verified=True,saved_log_evidence=old['saved_log_evidence'],recovery_source=str(old_path),new_photo_id=s.new_id)
 s.restart();nodes=s.camera();style=next(k for k in FREE if (n:=s.find(k,nodes)) and n.get('selected')=='true')
 s.tap(s.wait('last_photo'));s.wait('gallery_back');nodes=s.poll(lambda ns:any(n.get('resource-id','').endswith(':id/gallery_item') and s.visible(n) for n in ns),'Gallery missing')
 item=next(n for n in nodes if n.get('resource-id','').endswith(':id/gallery_item') and s.visible(n));s.tap(item)
 description=s.mono_review();s.report['direct_new_photo_review_verified']=True;s.snapshot('verified-disposable-mono')
 s.tap(s.wait('review_delete'));confirm=s.wait('button1');s.snapshot('verified-disposable-confirmation');s.disposable_tap(confirm,'delete')
 s.media_until(lambda ids:ids==s.initial_ids,'after-recovery-delete');s.report['photos_deleted']=1;s.check('Only verified disposable ID removed; original photo ID set restored',removed_id=s.new_id);s.report['passed']=True
except Exception as e:
 s.report['failure']=str(e);(s.output/'failure-traceback.txt').write_text(traceback.format_exc())
finally:
 if style:
  try:s.restart();s.camera();s.tap(s.style_target(style));s.camera(style)
  except Exception as e:s.report.update(passed=False,cleanup_error=str(e))
 if permissions is not None:
  assert s.permissions()==permissions and s.adb('shell','getprop','persist.sys.locale').strip()==locale and s.adb('shell','settings','get','system','font_scale').strip()==font
  s.report.update(permissions_unchanged=True,system_locale_unchanged=True,font_scale_unchanged=True)
 s.report['final_photo_ids_match_initial']=s.media('final')==s.initial_ids
 if s.dump_created:s.shell('rm','-f',s.dump)
 s.save()
print(json.dumps({'passed':s.report['passed'],'report':str(s.output/'evaluation.json')}))
sys.exit(0 if s.report['passed'] else 1)
