import urllib.request, json

BASE = 'http://www.YOUR_SERVER_DOMAIN:5004'
TOK = 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJlbWFpbCI6InhpbmlhbnlvdXNoZUBxcS5jb20iLCJleHAiOjE3ODk5NDg2ODcsImlhdCI6MTc4NzM1NjY4NywidG9rZW5fdmVyc2lvbiI6NjIsInVzZXJfaWQiOjF9.gPukphoVpsBLhv6VkPWG3Y5TjFj0vWWDf3x98e4HNlc'

def req(path, method='GET', obj=None):
    data = json.dumps(obj).encode() if obj else None
    r = urllib.request.Request(BASE + path, data=data, method=method,
                               headers={'Content-Type': 'application/json', 'Authorization': 'Bearer ' + TOK})
    try:
        return json.loads(urllib.request.urlopen(r, timeout=15).read().decode())
    except urllib.error.HTTPError as e:
        return {'code': e.code, 'message': e.read().decode()[:300]}
    except Exception as e:
        return {'error': str(e)}

print('=== 1. Conversations ===')
conv = req('/api/conversations')
if isinstance(conv, dict) and 'data' in conv:
    for c in conv['data']:
        print(f"  id={c.get('id')} name={c.get('username')} last={str(c.get('last_message'))[:30]!r}")
else:
    print(conv)

# Find image messages in each conversation
print('\n=== 2. Scan conversations for media messages ===')
for c in conv.get('data', []):
    fid = c.get('id')
    if fid is None or fid == 0:
        continue
    if fid < 0:
        msgs = req(f'/api/messages/0/{fid}?limit=100&offset=0')
    else:
        msgs = req(f'/api/messages/1/{fid}?limit=100&offset=0')
    if not isinstance(msgs, dict) or 'data' not in msgs:
        continue
    media = [m for m in msgs['data'] if m.get('media_type') or m.get('media_url')]
    if media:
        print(f'  Conversation {fid} ({c.get("username")}): {len(media)} media msgs')
        for m in media[-5:]:
            print(f"    id={m.get('id')} from={m.get('from_user_id')} type={m.get('media_type')!r} url={str(m.get('media_url'))[:80]!r}")
