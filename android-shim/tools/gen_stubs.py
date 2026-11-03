#!/usr/bin/env python3
"""Generate non-crashing Java stubs for android/androidx/app-only classes referenced by plugins.

usage: gen_stubs.py refs.json out_dir [handwritten_src_root...]
Classes whose outermost class has a handwritten .java/.kt under a handwritten root are skipped.
Every generated method logs once (Stub.hit) and returns a harmless default.
Re-run after Track D publishes a new reference list; then run tools/verify.sh.
"""
import json, os, re, sys
from collections import defaultdict

refs = json.load(open(sys.argv[1]))
out_dir = sys.argv[2]
hand_roots = sys.argv[3:]

# Approximate hierarchy (real android hierarchy flattened where intermediate classes are unreferenced).
EXT = {
    'android/app/Activity': 'android/content/ContextWrapper',
    'androidx/fragment/app/FragmentActivity': 'android/app/Activity',
    'androidx/appcompat/app/AppCompatActivity': 'androidx/fragment/app/FragmentActivity',
    'androidx/fragment/app/DialogFragment': 'androidx/fragment/app/Fragment',
    'com/google/android/material/bottomsheet/BottomSheetDialogFragment': 'androidx/fragment/app/DialogFragment',
    'android/app/AlertDialog': 'android/app/Dialog',
    'androidx/appcompat/app/AlertDialog': 'android/app/Dialog',
    'android/widget/TextView': 'android/view/View',
    'android/widget/Button': 'android/widget/TextView',
    'android/widget/EditText': 'android/widget/TextView',
    'android/widget/CompoundButton': 'android/widget/Button',
    'android/widget/CheckBox': 'android/widget/CompoundButton',
    'android/widget/RadioButton': 'android/widget/CompoundButton',
    'android/widget/Switch': 'android/widget/CompoundButton',
    'android/widget/ImageView': 'android/view/View',
    'android/widget/ImageButton': 'android/widget/ImageView',
    'android/widget/ProgressBar': 'android/view/View',
    'android/view/ViewGroup': 'android/view/View',
    'android/widget/LinearLayout': 'android/view/ViewGroup',
    'android/widget/FrameLayout': 'android/view/ViewGroup',
    'android/widget/ScrollView': 'android/widget/FrameLayout',
    'android/widget/RadioGroup': 'android/widget/LinearLayout',
    'android/widget/ListView': 'android/view/ViewGroup',
    'android/webkit/WebView': 'android/view/ViewGroup',
    'androidx/recyclerview/widget/RecyclerView': 'android/view/ViewGroup',
    'androidx/recyclerview/widget/LinearLayoutManager': 'androidx/recyclerview/widget/RecyclerView$LayoutManager',
    'android/graphics/drawable/ColorDrawable': 'android/graphics/drawable/Drawable',
    'android/graphics/drawable/GradientDrawable': 'android/graphics/drawable/Drawable',
    'android/graphics/drawable/StateListDrawable': 'android/graphics/drawable/Drawable',
    'android/widget/LinearLayout$LayoutParams': 'android/view/ViewGroup$MarginLayoutParams',
    'android/widget/FrameLayout$LayoutParams': 'android/view/ViewGroup$MarginLayoutParams',
    'android/view/ViewGroup$MarginLayoutParams': 'android/view/ViewGroup$LayoutParams',
    'android/content/res/Resources$NotFoundException': 'java/lang/RuntimeException',
    'com/lagradost/cloudstream3/syncproviders/providers/AniListApi': 'com/lagradost/cloudstream3/syncproviders/SyncAPI',
    'com/lagradost/cloudstream3/syncproviders/providers/SimklApi': 'com/lagradost/cloudstream3/syncproviders/SyncAPI',
    'android/view/animation/DecelerateInterpolator': 'java/lang/Object',
    'android/webkit/WebView$WebViewTransport': 'java/lang/Object',
    'android/text/style/ForegroundColorSpan': 'java/lang/Object',
    'android/text/style/StyleSpan': 'java/lang/Object',
}
IMPL = {
    'android/view/animation/DecelerateInterpolator': ['android/animation/TimeInterpolator'],
}
# Declared by name heuristics when not detected from bytecode.
IFACE_HINT = re.compile(r'(\$On\w+Listener|Listener$|Callback$|^android/content/DialogInterface$|^android/animation/TimeInterpolator$|^android/webkit/ValueCallback$|ViewParent$|^android/text/Editable$|^android/os/IBinder$|^android/text/TextWatcher$|^org/xmlpull/v1/XmlPullParser$)')
FORCE_CLASS = {'android/webkit/WebView$WebViewTransport', 'android/view/WindowInsets'}

# Abstract methods of well-known callback interfaces, so plugin implementations and callers line up
# even when no call site appears in the scanned bytecode.
IFACE_METHODS = {
    'android/view/View$OnClickListener': [('onClick', '(Landroid/view/View;)V')],
    'android/view/View$OnFocusChangeListener': [('onFocusChange', '(Landroid/view/View;Z)V')],
    'android/view/View$OnKeyListener': [('onKey', '(Landroid/view/View;ILandroid/view/KeyEvent;)Z')],
    'android/view/View$OnApplyWindowInsetsListener': [('onApplyWindowInsets', '(Landroid/view/View;Landroid/view/WindowInsets;)Landroid/view/WindowInsets;')],
    'android/content/DialogInterface$OnClickListener': [('onClick', '(Landroid/content/DialogInterface;I)V')],
    'android/content/DialogInterface$OnDismissListener': [('onDismiss', '(Landroid/content/DialogInterface;)V')],
    'android/content/DialogInterface$OnShowListener': [('onShow', '(Landroid/content/DialogInterface;)V')],
    'android/content/DialogInterface$OnKeyListener': [('onKey', '(Landroid/content/DialogInterface;ILandroid/view/KeyEvent;)Z')],
    'android/content/DialogInterface': [('dismiss', '()V'), ('cancel', '()V')],
    'android/widget/CompoundButton$OnCheckedChangeListener': [('onCheckedChanged', '(Landroid/widget/CompoundButton;Z)V')],
    'android/widget/TextView$OnEditorActionListener': [('onEditorAction', '(Landroid/widget/TextView;ILandroid/view/KeyEvent;)Z')],
    'android/text/TextWatcher': [('beforeTextChanged', '(Ljava/lang/CharSequence;III)V'), ('onTextChanged', '(Ljava/lang/CharSequence;III)V'), ('afterTextChanged', '(Landroid/text/Editable;)V')],
    'android/webkit/ValueCallback': [('onReceiveValue', '(Ljava/lang/Object;)V')],
    'android/animation/TimeInterpolator': [('getInterpolation', '(F)F')],
}

RETURN_SPECIAL = {
    'Landroid/content/Context;': 'cloudstream.desktop.androidshim.ShimRuntime.context()',
    'Landroid/content/res/Resources;': 'cloudstream.desktop.androidshim.ShimRuntime.context().getResources()',
    'Landroid/net/Uri;': 'android.net.Uri.EMPTY',
    'Landroid/content/SharedPreferences;': 'cloudstream.desktop.androidshim.ShimRuntime.context().getSharedPreferences("stub", 0)',
    'Landroid/os/Looper;': 'android.os.Looper.getMainLooper()',
    'Landroid/os/Bundle;': 'new android.os.Bundle()',
}


def _kotlin_decls():
    """Top-level classes declared by handwritten Kotlin files (file name may differ from class name)."""
    found = set()
    decl = re.compile(r'^(?:(?:public|internal|sealed|data|abstract|open|final|enum|annotation)\s+)*(?:class|object|interface)\s+(\w+)', re.M)
    for r in hand_roots:
        for dp, _, fs in os.walk(r):
            for f in fs:
                if f.endswith('.kt'):
                    t = open(os.path.join(dp, f), encoding='utf-8').read()
                    pm = re.search(r'^package\s+([\w.]+)', t, re.M)
                    if pm:
                        for m in decl.finditer(t):
                            found.add(pm.group(1).replace('.', '/') + '/' + m.group(1))
    return found


KOTLIN_HAND = _kotlin_decls()


# Real implementations live in other modules (webview-desktop) and must not be shadowed by a stub.
PROVIDED_ELSEWHERE = {'com/lagradost/cloudstream3/network/CloudflareKiller'}


def hand(outer):
    if outer in KOTLIN_HAND or outer in PROVIDED_ELSEWHERE:
        return True
    for r in hand_roots:
        for ext in ('.java', '.kt'):
            if os.path.exists(os.path.join(r, outer + ext)):
                return True
    return False


def outermost(n):
    return n.split('$')[0]


classes = {}  # internal name -> info
for n in refs['valid']:
    classes.setdefault(n, {'iface': refs['classes'][n]['iface'], 'enum': refs['classes'][n]['enum']})
# ensure outers of nested classes exist; super chain classes exist
changed = True
while changed:
    changed = False
    for n in list(classes):
        o = n.rsplit('$', 1)[0] if '$' in n else None
        for dep in (o, EXT.get(n)) + tuple(IMPL.get(n, [])):
            if dep and dep not in classes and not dep.startswith('java/'):
                classes[dep] = {'iface': False, 'enum': False}
                changed = True
for n, c in classes.items():
    if n not in FORCE_CLASS and IFACE_HINT.search(n):
        c['iface'] = True
    if n in FORCE_CLASS:
        c['iface'] = False

methods = defaultdict(dict)   # class -> (name, params) -> (ret_desc, static, params_desc)
ctors = defaultdict(dict)     # class -> params -> True
fields = defaultdict(dict)    # class -> name -> (desc, static)
for kind, owner, name, desc, static in refs['members']:
    if owner not in classes:
        continue
    if kind == 'Field':
        fields[owner][name] = (desc, static)
        continue
    params = desc[1:desc.index(')')]
    ret = desc[desc.index(')') + 1:]
    if name == '<init>':
        ctors[owner][params] = True
    elif name == '<clinit>':
        pass
    else:
        methods[owner].setdefault((name, params), (ret, static, params))

for _cls, _ms in IFACE_METHODS.items():
    if _cls in classes:
        for _name, _desc in _ms:
            _p = _desc[1:_desc.index(')')]
            methods[_cls].setdefault((_name, _p), (_desc[_desc.index(')') + 1:], False, _p))

for _cls, _ifs in IMPL.items():
    for _i in _ifs:
        for _name, _desc in IFACE_METHODS.get(_i, []):
            _p = _desc[1:_desc.index(')')]
            methods[_cls].setdefault((_name, _p), (_desc[_desc.index(')') + 1:], False, _p))

PRIM = {'V': 'void', 'Z': 'boolean', 'B': 'byte', 'S': 'short', 'C': 'char', 'I': 'int', 'J': 'long', 'F': 'float', 'D': 'double'}


def jtype(d):
    arr = 0
    while d.startswith('['):
        arr += 1
        d = d[1:]
    if d.startswith('L'):
        t = d[1:-1].replace('/', '.').replace('$', '.')
    else:
        t = PRIM[d]
    return t + '[]' * arr


def parse_params(p):
    res = []
    i = 0
    while i < len(p):
        j = i
        while p[j] == '[':
            j += 1
        if p[j] == 'L':
            j = p.index(';', j)
        res.append(p[i:j + 1])
        i = j + 1
    return res


def default_expr(ret, owner):
    if ret == 'V':
        return None
    if ret in ('Z',):
        return 'false'
    if ret in ('B', 'S', 'C', 'I'):
        return '0'
    if ret == 'J':
        return '0L'
    if ret == 'F':
        return '0f'
    if ret == 'D':
        return '0d'
    if ret == 'Ljava/lang/String;' or ret == 'Ljava/lang/CharSequence;':
        return '""'
    if ret in RETURN_SPECIAL:
        return RETURN_SPECIAL[ret]
    if ret.startswith('['):
        if ret.count('[') == 1:
            return 'new %s[0]' % jtype(ret[1:])
        return 'null'
    if ret in ('Ljava/util/List;', 'Ljava/util/Collection;', 'Ljava/lang/Iterable;'):
        return 'java.util.Collections.emptyList()'
    if ret == 'Ljava/util/Set;':
        return 'java.util.Collections.emptySet()'
    if ret == 'Ljava/util/Map;':
        return 'java.util.Collections.emptyMap()'
    if ret.startswith('L') and ret[1:-1] in classes:
        c = classes[ret[1:-1]]
        if ret[1:-1] == owner and not c['iface'] and not c['enum']:
            return 'this'
        if not c['iface'] and not c['enum'] and not hand(outermost(ret[1:-1])):
            return 'new %s()' % jtype(ret)
    return 'null'


def emit(n, indent, nested_of):
    c = classes[n]
    simple = n.split('$')[-1] if '$' in n else n.split('/')[-1]
    pad = '    ' * indent
    L = []
    kind = 'interface' if c['iface'] else ('enum' if c['enum'] else 'class')
    ext = EXT.get(n)
    head = '%spublic %s%s %s' % (pad, 'static ' if nested_of else '', kind, simple)
    if kind == 'class' and ext:
        head += ' extends ' + ext.replace('/', '.').replace('$', '.')
    impls = [i.replace('/', '.').replace('$', '.') for i in IMPL.get(n, [])]
    if impls and kind == 'class':
        head += ' implements ' + ', '.join(impls)
    L.append(head + ' {')
    fq = n.replace('/', '.').replace('$', '.')
    if kind == 'enum':
        consts = sorted(f for f, (d, s) in fields[n].items() if d == 'L%s;' % n and s)
        if not consts:
            consts = ['UNKNOWN']
        L.append(pad + '    ' + ', '.join(consts) + ';')
    for f, (d, s) in sorted(fields[n].items()):
        if kind == 'enum' and d == 'L%s;' % n:
            continue
        init = default_expr(d, n) if d != 'L%s;' % n else 'null'
        if d.startswith('L') and d[1:-1] == n and s and kind == 'class':
            init = 'new %s()' % simple
        init = init if init not in (None, 'this') else 'null'
        if kind == 'interface':
            L.append('%s    %s %s = %s;' % (pad, jtype(d), f, init))
        else:
            L.append('%s    public %s%s %s = %s;' % (pad, 'static ' if s else '', jtype(d), f, init))
    if kind == 'class':
        cs = dict(ctors[n])
        cs.pop('', None)
        L.append('%s    public %s() { }' % (pad, simple))
        for params in cs:
            ps = parse_params(params)
            args = ', '.join('%s a%d' % (jtype(p), i) for i, p in enumerate(ps))
            L.append('%s    public %s(%s) { super(); %s }' % (
                pad, simple, args, 'cloudstream.desktop.androidshim.Stub.hit("%s.<init>");' % fq))
    for (name, params), (ret, static, _) in sorted(methods[n].items()):
        ps = parse_params(params)
        args = ', '.join('%s a%d' % (jtype(p), i) for i, p in enumerate(ps))
        body_hit = 'cloudstream.desktop.androidshim.Stub.hit("%s.%s");' % (fq, name)
        if kind == 'interface':
            L.append('%s    %s %s(%s);' % (pad, jtype(ret), name, args))
            continue
        d = default_expr(ret, n)
        if static and d == 'this':
            d = 'null'
        body = body_hit + (' return %s;' % d if d else '')
        vt = view_tree_body(name, ret, ps, static)
        if vt is not None:
            body = vt
        L.append('%s    public %s%s %s(%s) { %s }' % (pad, 'static ' if static else '', jtype(ret), name, args, body))
    # nested
    prefix = n + '$'
    for m in sorted(classes):
        if m.startswith(prefix) and '$' not in m[len(prefix):]:
            L.extend(emit(m, indent + 1, n))
    L.append(pad + '}')
    return L


VT = 'cloudstream.desktop.androidshim.ViewTree'


def view_tree_body(name, ret, ps, static):
    """View-hierarchy methods record into ViewTree (dialogs need to know which WebViews they show)."""
    if static:
        return None
    first_is_view = bool(ps) and ps[0] == 'Landroid/view/View;'
    if name == 'addView' and first_is_view:
        return '%s.add(this, a0);' % VT
    if name == 'removeView' and first_is_view:
        return '%s.remove(this, a0);' % VT
    if name == 'removeAllViews' and not ps:
        return '%s.clear(this);' % VT
    if name == 'getChildCount' and not ps:
        return 'return %s.count(this);' % VT
    if name == 'getChildAt' and len(ps) == 1:
        return 'Object c = %s.childAt(this, a0); return c instanceof android.view.View ? (android.view.View) c : new android.view.View();' % VT
    if name == 'getParent' and not ps and ret == 'Landroid/view/ViewParent;':
        return 'Object p = %s.parent(this); return p instanceof android.view.ViewParent ? (android.view.ViewParent) p : null;' % VT
    return None


count = 0
for n in sorted(classes):
    if '$' in n:
        continue
    if hand(n):
        continue
    pkg, simple = n.rsplit('/', 1)
    lines = ['// GENERATED by tools/gen_stubs.py - do not edit. Stub for a class plugins reference.',
             'package %s;' % pkg.replace('/', '.'), '']
    lines.extend(emit(n, 0, None))
    path = os.path.join(out_dir, n + '.java')
    os.makedirs(os.path.dirname(path), exist_ok=True)
    open(path, 'w', newline='\n').write('\n'.join(lines) + '\n')
    count += 1
print('generated', count, 'top-level classes')
