import os, subprocess, glob, sys
root = r'C:\Users\20676\source\LQXM'
cfg = {}
for line in open(os.path.join(root, 'backend', '.env'), encoding='utf-8', errors='ignore'):
    line = line.strip()
    if not line or line.startswith('#') or '=' not in line:
        continue
    k, v = line.split('=', 1)
    cfg[k.strip()] = v.strip().strip('"').strip("'")
env = os.environ.copy()
env['DB_USERNAME'] = cfg.get('DB_USERNAME', 'root')
env['DB_PASSWORD'] = cfg.get('DB_PASSWORD', '')
env['JAVA_TOOL_OPTIONS'] = '-Dstdout.encoding=UTF-8 -Dfile.encoding=UTF-8'
jdk = r'D:\Program Files\Java\jdk-21.0.12\bin'
jar = [j for j in glob.glob(os.path.join(os.environ['USERPROFILE'], '.m2', 'repository', 'com', 'mysql', 'mysql-connector-j', '**', '*.jar'), recursive=True) if 'sources' not in j][0]
cls = os.path.join(root, 'tools', '_cls')
name = sys.argv[1]
src = os.path.join(root, 'tools', name + '.java')
r = subprocess.run([os.path.join(jdk, 'javac.exe'), '-encoding', 'UTF-8', '-d', cls, src],
                   capture_output=True, text=True, env=env, encoding='utf-8', errors='replace')
if r.returncode != 0:
    print('COMPILE FAIL'); print(r.stdout[-3000:]); print(r.stderr[-3000:]); sys.exit(1)
r = subprocess.run([os.path.join(jdk, 'java.exe'), '-Dstdout.encoding=UTF-8', '-cp', cls + ';' + jar] + [name] + sys.argv[2:],
                   capture_output=True, env=env, encoding='utf-8', errors='replace')
out = r.stdout
open(os.path.join(root, 'tools', name + '_out.txt'), 'w', encoding='utf-8').write(out)
print(out)
