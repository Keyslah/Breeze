"""Small ADB helper for this project's observed UI; no coordinate assumptions."""
import subprocess, xml.etree.ElementTree as ET, re, sys, pathlib, os
ADB=['adb','-s',os.environ.get('BREEZE_ADB_SERIAL','19151FDF6005K7')]
def command(*args): return subprocess.check_output(ADB+list(args))
def nodes():
    command('shell','uiautomator','dump','/sdcard/breeze-ui.xml')
    return ET.fromstring(command('shell','cat','/sdcard/breeze-ui.xml')).iter('node')
if sys.argv[1]=='list':
    for n in nodes():
        if n.get('text') or n.get('content-desc'): print(n.get('text') or n.get('content-desc'), n.get('bounds'))
elif sys.argv[1]=='click':
    candidates=[n for n in nodes() if n.get('text')==sys.argv[2] and n.get('clickable')=='true']
    if len(candidates)!=1: raise RuntimeError(f'Expected one visible clickable element, got {len(candidates)}')
    x1,y1,x2,y2=map(int,re.findall(r'\d+',candidates[0].get('bounds')))
    command('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
elif sys.argv[1]=='shot': pathlib.Path(sys.argv[2]).write_bytes(command('exec-out','screencap','-p'))
