from pykeepass import PyKeePass
import sys, traceback

kdbx = r'C:\Users\metav\AppData\Roaming\Google\AndroidStudio2025.2.2\c.kdbx'
keyfile = r'C:\WindowsEntwicklung\Android\ChordProgressionHelper\c.pwd.unwrapped.bin'

print('Python executable:', sys.executable)
print('Trying to open KDBX:', kdbx)
print('Using keyfile:', keyfile)

try:
    kp = PyKeePass(kdbx, keyfile=keyfile)
    print('Opened DB with keyfile only. Entries count:', len(kp.entries))
except Exception as e:
    print('Keyfile-only open failed:')
    traceback.print_exc()
    kp = None

if kp is None:
    try:
        kp = PyKeePass(kdbx)
        print('Opened DB with no keyfile (password-only). Entries count:', len(kp.entries))
    except Exception as e:
        print('Open DB without keyfile failed:')
        traceback.print_exc()
        sys.exit(1)

candidates = []
for entry in kp.entries:
    concat = ' '.join([str(entry.title), str(entry.username), str(entry.url), str(entry.notes)]).lower()
    if any(token in concat for token in ['keystore', '.jks', 'key0', 'keystore.jks', 'android', 'alias']):
        candidates.append(entry)

print('Found', len(candidates), 'candidate entries matching keystore keywords')
for e in candidates:
    print('---')
    print('Title:', e.title)
    print('Username:', e.username)
    print('URL:', e.url)
    print('Notes:', (e.notes[:200] + '...') if e.notes and len(e.notes)>200 else e.notes)
    print('Password:', e.password)

if not candidates:
    print('\nNo direct matches. Listing titles of all entries (first 100):')
    for i,entry in enumerate(kp.entries[:100]):
        print(i+1, entry.title)

