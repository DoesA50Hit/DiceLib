"""Builds the DiceLib Guide.

    python docs/build_guide.py           copy the example classes into the pages, then write DiceLib-Guide/index.html
    python docs/build_guide.py --check   fail if a page's example code differs from src/examples (changes nothing)
    python docs/build_guide.py --wiki <folder>   also write the pages as a GitHub wiki into <folder>

The Markdown pages are the source for the prose. src/examples is the source for every full example class: a ```java
block whose class has a file in src/examples is replaced by that file (without its package line and imports), so the
code in the guide is the code `./gradlew build` compiles. The site is guide-template.html with the navigation, the
pages and the search index filled in.
"""
import html
import json
import os
import re
import sys

DOCS = os.path.dirname(os.path.abspath(__file__))
GUIDE = os.path.join(DOCS, 'DiceLib-Guide')
EXAMPLES = os.path.join(DOCS, '..', 'src', 'examples', 'java', 'org', '_50dozenhits', 'dice_library', 'examples')
TEMPLATE = os.path.join(DOCS, 'guide-template.html')

TOP_CLASS = re.compile(r'^(?:(?://[^\n]*|@[^\n]*)\n)*public (?:final |abstract )*(?:class|record|enum|interface|@interface) (\w+)', re.M)


# ---- example sync -------------------------------------------------------------------------------------------------

def example_body(name):
    path = os.path.join(EXAMPLES, name + '.java')
    if not os.path.exists(path):
        return None
    lines = open(path, encoding='utf-8').read().split('\n')
    i = 0
    while i < len(lines) and (lines[i].startswith(('package ', 'import ')) or not lines[i].strip()
                              or lines[i].startswith('// From the DiceLib Guide')):
        i += 1
    return '\n'.join(lines[i:]).rstrip('\n') + '\n'


def sync_page(text):
    def replace(match):
        block = match.group(1)
        tops = list(TOP_CLASS.finditer(block))
        if not tops:
            return match.group(0)
        bodies = [example_body(t.group(1)) for t in tops]
        if any(b is None for b in bodies):
            return match.group(0)
        return '```java\n' + '\n'.join(bodies) + '```'
    return re.sub(r'```java\n(.*?)```', replace, text, flags=re.S)


# ---- markdown to html ---------------------------------------------------------------------------------------------

def slug(text):
    return re.sub(r'[^a-z0-9]+', '-', text.lower()).strip('-')


KEYWORDS = set('''abstract assert boolean break byte case catch char class const continue default do double else enum extends
final finally float for goto if implements import instanceof int interface long native new package private protected public
return short static strictfp super switch synchronized this throw throws transient try void volatile while true false null
var record yield sealed permits'''.split())

JAVA_TOKEN = re.compile(r'''(?P<com>//[^\n]*|/\*.*?\*/)|(?P<str>"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])')|(?P<ann>@\w+)'''
                        r'''|(?P<num>\b(?:0x[0-9A-Fa-f]+|\d+(?:\.\d+)?(?:[eE][-+]?\d+)?)[fFdDlL]?\b)|(?P<word>\b[A-Za-z_]\w*\b)''', re.S)
JSON_TOKEN = re.compile(r'(?P<key>"(?:\\.|[^"\\])*"(?=\s*:))|(?P<str>"(?:\\.|[^"\\])*")|(?P<num>-?\b\d+(?:\.\d+)?\b)|(?P<kw>\btrue\b|\bfalse\b|\bnull\b)')


def highlight(code, lang):
    if lang not in ('java', 'json'):
        return html.escape(code, quote=False)
    pattern = JAVA_TOKEN if lang == 'java' else JSON_TOKEN
    out, pos = [], 0
    for m in pattern.finditer(code):
        out.append(html.escape(code[pos:m.start()], quote=False))
        kind, text = m.lastgroup, m.group(0)
        if kind == 'word':
            kind = 'kw' if text in KEYWORDS else 'typ' if text[0].isupper() else None
        if kind:
            out.append('<span class="t-%s">%s</span>' % (kind, html.escape(text, quote=False)))
        else:
            out.append(html.escape(text, quote=False))
        pos = m.end()
    out.append(html.escape(code[pos:], quote=False))
    return ''.join(out)


class Site:
    def __init__(self, pages):
        self.pages = pages                       # title -> markdown
        self.ids = {title: slug(title) for title in pages}

    def link(self, target, note_id):
        page, _, section = target.partition('#')
        page_id = self.ids.get(page) if page else note_id
        if page and page_id is None:
            print('warning: link to a missing page [[%s]]' % target)
            return html.escape(target)
        href = page_id + ('--' + slug(section) if section else '')
        label = (page + ' › ' + section) if page and section else (section or page)
        return '<a class="wl" href="#%s">%s</a>' % (href, html.escape(label))

    def inline(self, text, note_id):
        parts = re.split(r'(`[^`]*`)', text)
        out = []
        for part in parts:
            if part.startswith('`') and part.endswith('`') and len(part) > 1:
                out.append('<code>%s</code>' % html.escape(part[1:-1], quote=False))
                continue
            esc = html.escape(part, quote=False)
            esc = re.sub(r'\[\[([^\]]+)\]\]', lambda m: self.link(html.unescape(m.group(1)), note_id), esc)
            out.append(esc)
        return ''.join(out)

    def cells(self, row, note_id):
        row = row.strip().strip('|')
        cells, cur, in_code, i = [], '', False, 0
        while i < len(row):
            c = row[i]
            if c == '\\' and i + 1 < len(row) and row[i + 1] == '|':
                cur += '|'; i += 2; continue
            if c == '`':
                in_code = not in_code
            if c == '|' and not in_code:
                cells.append(cur); cur = ''
            else:
                cur += c
            i += 1
        cells.append(cur)
        return [self.inline(c.strip(), note_id) for c in cells]

    def render(self, title):
        note_id = self.ids[title]
        lines = self.pages[title].split('\n')
        out, sections, i = [], [], 0
        search = []                               # (section title, anchor, text parts)
        current = [title, note_id, []]
        pager = None

        def text_of(s):
            current[2].append(re.sub(r'\[\[(?:[^\]|#]*#)?([^\]|]+)\]\]', r'\1', s).replace('`', ''))

        while i < len(lines):
            line = lines[i]
            if line.startswith('```'):
                lang = line[3:].strip() or 'text'
                j = i + 1
                code = []
                while j < len(lines) and not lines[j].startswith('```'):
                    code.append(lines[j]); j += 1
                body = '\n'.join(code)
                out.append('<div class="code"><button class="copy" title="Copy">Copy</button><pre><code class="lang-%s">%s</code></pre></div>'
                           % (lang, highlight(body, lang)))
                i = j + 1
                continue
            m = re.match(r'^(#{1,3}) (.*)$', line)
            if m:
                level, text = len(m.group(1)), m.group(2).strip()
                search.append(tuple(current))
                if level == 1:
                    out.append('<h1 id="%s--%s">%s</h1>' % (note_id, slug(text), html.escape(text)))
                    current = [text, note_id, []]
                else:
                    anchor = note_id + '--' + slug(text)
                    out.append('<h%d id="%s">%s<a class=anchor href=#%s>#</a></h%d>' % (level, anchor, html.escape(text), anchor, level))
                    current = [text, anchor, []]
                    if level == 2:
                        sections.append((text, anchor))
                i += 1
                continue
            if line.startswith('|') and i + 1 < len(lines) and re.match(r'^\|[\s:|-]+\|$', lines[i + 1].strip()):
                head = self.cells(line, note_id)
                rows = []
                j = i + 2
                while j < len(lines) and lines[j].startswith('|'):
                    rows.append(self.cells(lines[j], note_id)); text_of(lines[j]); j += 1
                out.append('<div class="table"><table><thead><tr>%s</tr></thead><tbody>%s</tbody></table></div>' % (
                    ''.join('<th>%s</th>' % c for c in head),
                    ''.join('<tr>%s</tr>' % ''.join('<td>%s</td>' % c for c in r) for r in rows)))
                i = j
                continue
            m = re.match(r'^> \[!(\w+)\] ?(.*)$', line)
            if m:
                kind, ctitle = m.group(1).lower(), m.group(2)
                body = []
                j = i + 1
                while j < len(lines) and lines[j].startswith('>'):
                    body.append(lines[j][1:].strip()); j += 1
                text_of(' '.join(body))
                out.append('<div class="callout c-%s"><div class="ctitle">%s</div><div class="cbody"><p>%s</p></div></div>'
                           % (kind, html.escape(ctitle), self.inline(' '.join(body), note_id)))
                i = j
                continue
            if re.match(r'^(- |\d+\. )', line):
                ordered = bool(re.match(r'^\d+\. ', line))
                items = []
                j = i
                while j < len(lines) and re.match(r'^(- |\d+\. )', lines[j]):
                    items.append(re.sub(r'^(- |\d+\. )', '', lines[j])); text_of(items[-1]); j += 1
                tag = 'ol' if ordered else 'ul'
                first = int(re.match(r'^\d+', line).group(0)) if ordered else 1
                start = ' start="%d"' % first if first != 1 else ''     # a list that carries on after a code block
                out.append('<%s%s>%s</%s>' % (tag, start, ''.join('<li>%s</li>' % self.inline(it, note_id) for it in items), tag))
                i = j
                continue
            if line.strip() == '---':
                out.append('<hr>')
                i += 1
                continue
            if line.startswith(('← Previous:', 'Next:')):
                pager = re.findall(r'\[\[([^\]]+)\]\]', line)
                prev = line.startswith('← Previous:')
                links = []
                for k, target in enumerate(pager):
                    arrow_left = prev and k == 0
                    href = self.ids.get(target, slug(target))
                    links.append('<a href="#%s">%s</a>' % (href, ('← ' + target) if arrow_left else (target + ' →')))
                out.append('<nav class="pager">%s</nav>' % ''.join(links))
                i += 1
                continue
            if not line.strip():
                i += 1
                continue
            para = [line]
            j = i + 1
            while j < len(lines) and lines[j].strip() and not re.match(r'^(```|#|\||> |- |\d+\. |---$|← Previous:|Next:)', lines[j]):
                para.append(lines[j]); j += 1
            text_of(' '.join(para))
            out.append('<p>%s</p>' % self.inline(' '.join(para), note_id))
            i = j
        search.append(tuple(current))
        article = '<article id="%s" data-title="%s" hidden>%s</article>' % (note_id, html.escape(title), '\n'.join(out))
        entries = [{'n': title, 's': s, 'a': a, 't': ' '.join(t)} for s, a, t in search if t or s == title]
        return article, sections, entries


def nav(site):
    home = site.pages['Home']
    table = home.split('## Every note', 1)[1].split('\n## ', 1)[0]
    groups = []
    for row in table.split('\n'):
        m = re.match(r'^\| ([^|]+) \| (.+) \|$', row.strip())
        if not m or m.group(1).strip() in ('Group', '---'):
            continue
        groups.append((m.group(1).strip(), re.findall(r'\[\[([^\]]+)\]\]', m.group(2))))
    if groups:
        groups[0][1].insert(0, 'Home')
    return groups


# ---- github wiki --------------------------------------------------------------------------------------------------

def wiki_name(title):
    return title.replace(' ', '-')


def wiki_anchor(heading):
    # the id GitHub gives a heading: lower case, punctuation dropped, spaces to hyphens
    return re.sub(r'[^a-z0-9 _-]', '', heading.lower()).replace(' ', '-')


def wiki_links(text):
    def link(match):
        page, _, section = match.group(1).partition('#')
        label = (page + ' › ' + section) if page and section else (section or page)
        return '[%s](%s%s)' % (label, wiki_name(page), '#' + wiki_anchor(section) if section else '')
    parts = re.split(r'(`[^`]*`)', text)
    return ''.join(part if part.startswith('`') else re.sub(r'\[\[([^\]]+)\]\]', link, part) for part in parts)


def write_wiki(site, out):
    """One file per page, named the way a GitHub wiki names pages, with [[links]] turned into Markdown links. The
    title line and the previous/next footer are left out: the wiki shows the title itself and _Sidebar.md is the
    navigation."""
    os.makedirs(out, exist_ok=True)
    for title, text in site.pages.items():
        lines = text.rstrip('\n').split('\n')
        if lines and lines[0].startswith('# '):
            lines = lines[1:]
        while lines and (not lines[-1].strip() or lines[-1].strip() == '---' or lines[-1].startswith(('← Previous:', 'Next:'))):
            lines.pop()
        body, fenced = [], False
        for line in lines:
            fence = line.startswith('```')
            if fence:
                fenced = not fenced
            body.append(line if fenced or fence else wiki_links(line))
        open(os.path.join(out, wiki_name(title) + '.md'), 'w', encoding='utf-8', newline='\n').write('\n'.join(body).strip('\n') + '\n')
    side = []
    for group, notes in nav(site):
        side.append('**%s**\n' % group)
        side.extend('- [%s](%s)' % (title, wiki_name(title)) for title in notes if title in site.pages)
        side.append('')
    open(os.path.join(out, '_Sidebar.md'), 'w', encoding='utf-8', newline='\n').write('\n'.join(side).rstrip('\n') + '\n')
    print('wrote the wiki: %d pages into %s' % (len(site.pages), out))


def build():
    pages = {}
    changed = []
    for name in sorted(os.listdir(GUIDE)):
        if not name.endswith('.md'):
            continue
        path = os.path.join(GUIDE, name)
        text = open(path, encoding='utf-8').read()
        synced = sync_page(text)
        if synced != text:
            changed.append(name)
            if '--check' not in sys.argv:
                open(path, 'w', encoding='utf-8', newline='\n').write(synced)
        pages[name[:-3]] = synced
    if '--check' in sys.argv:
        if changed:
            print('example code differs from src/examples in: ' + ', '.join(changed))
            sys.exit(1)
        print('every page matches src/examples')
        return
    for name in changed:
        print('synced examples into', name)

    site = Site(pages)
    rendered = {title: site.render(title) for title in pages}
    nav_html = []
    for group, notes in nav(site):
        rows = []
        for title in notes:
            if title not in rendered:
                print('warning: Home lists a missing page', title)
                continue
            note_id = site.ids[title]
            subs = ''.join('<a class="nsub" href="#%s">%s</a>' % (a, html.escape(s)) for s, a in rendered[title][1])
            twist = ('<button class="twist" data-for="%s" aria-label="Show sections" aria-expanded="false"><span class="caret"></span></button>' % note_id
                     if subs else '<span class="twist-pad"></span>')
            rows.append('<div class="nrow">%s<a class="nlink" data-note="%s" href="#%s">%s</a></div><div class="nsubs" data-for="%s">%s</div>'
                        % (twist, note_id, note_id, html.escape(title), note_id, subs))
        nav_html.append('<div class="ngroup" data-group="%s"><button class="ghead" aria-expanded="true"><span class="caret"></span>%s</button><div class="gbody">%s</div></div>'
                        % (slug(group), html.escape(group), ''.join(rows)))
    listed = {t for _, notes in nav(site) for t in notes}
    for title in pages:
        if title not in listed:
            print('warning: page missing from the Home table:', title)

    order = [t for _, notes in nav(site) for t in notes if t in rendered] + [t for t in pages if t not in listed]
    articles = '\n'.join(rendered[t][0] for t in order)
    search = [e for t in order for e in rendered[t][2]]
    template = open(TEMPLATE, encoding='utf-8').read()
    page = (template.replace('{{NAV}}', ''.join(nav_html))
            .replace('{{ARTICLES}}', articles)
            .replace('{{SEARCH}}', json.dumps(search, ensure_ascii=False)))
    open(os.path.join(GUIDE, 'index.html'), 'w', encoding='utf-8', newline='\n').write(page)
    print('wrote index.html: %d pages, %d search entries' % (len(order), len(search)))
    if '--wiki' in sys.argv:
        write_wiki(site, sys.argv[sys.argv.index('--wiki') + 1])


if __name__ == '__main__':
    build()
