import json,sys
d=json.load(open(sys.argv[1]))
for c in d['valid']: print(c, 'I' if d['classes'][c]['iface'] else '', 'E' if d['classes'][c]['enum'] else '')
