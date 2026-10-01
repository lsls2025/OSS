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

# Official group -1001: get all media messages
msgs = req('/api/messages/0/-1001?limit=250&offset=0')
if isinstance(msgs, dict) and 'data' in msgs:
    data = msgs['data']
    print(f'Total msgs in official group: {len(data)}')
    media = [m for m in data if m.get('media_type') or m.get('media_url')]
    print(f'Media msgs: {len(media)}')
    print('\nAll media messages (newest first):')
    for m in media:
        url = m.get('media_url') or ''
        status = 'OK' if url else 'EMPTY_URL!'
        print(f"  id={m.get('id')} from={m.get('from_user_id')} type={m.get('media_type')!r} url={str(url)[:70]!r} [{status}]")
else:
    print(msgs)
