#!/usr/bin/env python3
"""Publish the Clipra design package and plan to GitHub.

Run from the repository root in a shell where `git` and `gh` are logged in:

    python3 scripts/plan/publish.py            # git branch + push + PR, then issues and project items
    python3 scripts/plan/publish.py --no-git   # only issues and project items (uses the current HEAD for image links)

What it does:
  1. git: creates branch `design/plan-v1` (if on main), commits everything, pushes, opens a pull request.
  2. GitHub: creates labels and milestones, creates every issue in scripts/plan/issues.json that does not exist yet
     (matched by title), fills in cross references, links tasks as sub-issues of their epic, and adds every issue
     to the organization project (ideaconnect / 6).
It is safe to run again: existing issues are reused, missing pieces are added.
A log is written to scripts/plan/.publish-log.json.
"""
import json, os, re, subprocess, sys, tempfile, time

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
PLAN = os.path.join(ROOT, 'scripts', 'plan', 'issues.json')
LOG = os.path.join(ROOT, 'scripts', 'plan', '.publish-log.json')
BRANCH = 'design/plan-v1'
TRAILER = ('\n\nCo-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>\n'
           'Claude-Session: https://claude.ai/code/session_01EcdX5CZBG3AxJkX83mFqZw')
PR_FOOTER = ('\n\n🤖 Generated with [Claude Code](https://claude.com/claude-code)\n\n'
             'https://claude.ai/code/session_01EcdX5CZBG3AxJkX83mFqZw')

log = {'steps': [], 'errors': [], 'issues': {}}


def save_log():
    with open(LOG, 'w') as f:
        json.dump(log, f, indent=1, ensure_ascii=False)


def step(msg):
    print('==> ' + msg, flush=True)
    log['steps'].append(msg)
    save_log()


def run(args, check=True, quiet=False, retries=0):
    for attempt in range(retries + 1):
        p = subprocess.run(args, cwd=ROOT, capture_output=True, text=True)
        if p.returncode == 0:
            return p.stdout.strip()
        err = (p.stderr or p.stdout).strip()
        if attempt < retries and ('rate limit' in err.lower() or 'secondary' in err.lower() or '502' in err or '503' in err):
            wait = 30 * (attempt + 1)
            print(f'    rate limited, waiting {wait} s', flush=True)
            time.sleep(wait)
            continue
        if check:
            log['errors'].append({'cmd': ' '.join(args[:6]), 'error': err[-800:]})
            save_log()
            raise SystemExit(f'Command failed: {" ".join(args[:6])}\n{err}')
        if not quiet:
            print('    warning: ' + err[-300:], flush=True)
        return None


def gh_json(args):
    out = run(['gh'] + args)
    return json.loads(out) if out else None


# ------------------------------------------------------------------ git
def do_git(repo):
    step('git: preparing branch')
    cur = run(['git', 'rev-parse', '--abbrev-ref', 'HEAD'])
    if cur == 'main':
        exists = run(['git', 'rev-parse', '--verify', '--quiet', BRANCH], check=False, quiet=True)
        run(['git', 'checkout', BRANCH] if exists else ['git', 'checkout', '-b', BRANCH])
        cur = BRANCH
    run(['git', 'add', '-A'])
    staged = run(['git', 'diff', '--cached', '--name-only'])
    if staged:
        msg = ('Add Clipra design, product docs, architecture and plan\n\n'
               '- design/: every phone and tablet screen as PNG, states, flows, static HTML snapshots,\n'
               '  canvas sources, tokens and the design system\n'
               '- docs/: product spec, monetization (two export ads, Clipra Pro 10 PLN), architecture,\n'
               '  third-party list, roadmap\n'
               '- CLAUDE.md: how to use the design and work in this repo\n'
               '- scripts/plan/: the plan as data and the script that publishes it to Project 6\n'
               '- Relicense from GPL-3.0 to Apache-2.0 (software not yet published)' + TRAILER)
        with tempfile.NamedTemporaryFile('w', delete=False, suffix='.txt') as f:
            f.write(msg)
        run(['git', 'commit', '-F', f.name])
        os.unlink(f.name)
    step('git: pushing ' + cur)
    if run(['git', 'push', '-u', 'origin', cur], check=False) is None:
        print('    SSH push failed, retrying over HTTPS with the gh credential helper', flush=True)
        run(['git', '-c', 'credential.helper=', '-c', 'credential.helper=!gh auth git-credential',
             'push', '-u', f'https://github.com/{repo}.git', cur])
    sha = run(['git', 'rev-parse', 'HEAD'])
    step('github: pull request')
    prs = gh_json(['pr', 'list', '--repo', repo, '--head', cur, '--state', 'all', '--json', 'number,url'])
    if prs:
        log['pr'] = prs[0]['url']
    else:
        body = ('Adds the complete Clipra design export, the product spec, the monetization rules, the architecture proposal, '
                'CLAUDE.md and the implementation plan.\n\n'
                '- Start with `CLAUDE.md` and `design/README.md`.\n'
                '- The plan is published as issues and added to Project 6 (ClipraPlan); `docs/plan/ROADMAP.md` is the overview.\n'
                '- License changes from GPL-3.0 to Apache-2.0 so the app can use the closed Google SDKs it needs (ads, billing, ML Kit) '
                'alongside permissive libraries.\n\n'
                'Issue screenshots link to this branch by commit SHA, so they keep working after merge.' + PR_FOOTER)
        url = run(['gh', 'pr', 'create', '--repo', repo, '--base', 'main', '--head', cur,
                   '--title', 'Add Clipra design, docs, CLAUDE.md and the implementation plan', '--body', body])
        log['pr'] = url
    save_log()
    return sha


# ------------------------------------------------------------------ bodies
def render(issue, data, sha, nums, titles_by_key):
    repo = data['repo']
    blob = f'https://github.com/{repo}/blob/{sha}/'
    def ref(k):
        n = nums.get(k)
        return f'#{n}' if n else f'`{k}`'
    L = []
    ms = next(m for m in data['milestones'] if m['id'] == issue['milestone'])
    if issue['parent']:
        L.append(f'> Part of {ref(issue["parent"])} · {ms["title"]}')
    else:
        L.append(f'> Epic for milestone **{ms["title"]}**')
    L.append('')
    L.append('## What we want to achieve' if issue['kind'] == 'epic' else '## Goal')
    L.append(issue['goal'])
    if issue['design']:
        L += ['', '## Design', '']
        def tag(d):
            return f'<a href="{blob}{d["path"]}"><img src="{blob}{d["path"]}?raw=true" width="{d["w"]}" alt="{d["cap"]}"></a>'
        wide = [d for d in issue['design'] if '/flows/' in d['path']]
        narrow = [d for d in issue['design'] if '/flows/' not in d['path']]
        for d in wide:
            L += [f'**{d["cap"]}**', '', tag(d), '']
        if narrow:
            L.append('| ' + ' | '.join(d['cap'] for d in narrow) + ' |')
            L.append('|' + '---|' * len(narrow))
            L.append('| ' + ' | '.join(tag(d) for d in narrow) + ' |')
        boards = []
        for d in issue['design']:
            if '/flows/' in d['path']:
                continue
            b = os.path.basename(d['path'])[:-4].split('--')[0]
            if b not in boards:
                boards.append(b)
        if boards:
            L += ['', 'Sources: ' + ', '.join(f'[`{b}.dc.html`]({blob}design/canvas/{b}.dc.html)' for b in boards)
                  + ' · static snapshots: ' + ', '.join(f'[`{b}.html`]({blob}design/static/{b}.html)' for b in boards)
                  + ' · index: [design/README.md](' + blob + 'design/README.md)']
    L += ['', '## Scope'] + [f'- {s}' for s in issue['scope']]
    L += ['', '## Exit criteria' if issue['kind'] == 'epic' else '## Acceptance criteria'] + [f'- [ ] {a}' for a in issue['acceptance']]
    if issue['kind'] == 'epic':
        kids = [i for i in data['issues'] if i['parent'] == issue['key']]
        L += ['', '## Tasks'] + [f'- [ ] {ref(k["key"])} {k["title"]}' for k in kids]
    if issue['notes']:
        L += ['', '## Risks' if issue['kind'] == 'epic' else '## Technical notes'] + [f'- {n}' for n in issue['notes']]
    if issue['depends']:
        L += ['', '## Depends on'] + [f'- {ref(d)} {titles_by_key[d]}' for d in issue['depends']]
    if issue['refs']:
        def link(r):
            path = r.split(' ')[0]
            rest = r[len(path):]
            return f'[{path}]({blob}{path}){rest}'
        L += ['', '## References'] + [f'- {link(r)}' for r in issue['refs']]
    L += ['', f'<sub>Plan key `{issue["key"]}` · generated from `scripts/plan/issues.json`</sub>']
    return '\n'.join(L)


# ------------------------------------------------------------------ main
def main():
    no_git = '--no-git' in sys.argv
    data = json.load(open(PLAN))
    repo = data['repo']
    owner, number = data['project']['owner'], str(data['project']['number'])
    titles = [i['title'] for i in data['issues']]
    assert len(titles) == len(set(titles)), 'issue titles must be unique'

    if '--dry-run' in sys.argv:
        out = os.path.join(ROOT, 'scripts', 'plan', '.bodies')
        os.makedirs(out, exist_ok=True)
        fake = {i['key']: n + 1 for n, i in enumerate(data['issues'])}
        tk = {i['key']: i['title'] for i in data['issues']}
        for i in data['issues']:
            open(os.path.join(out, i['key'] + '.md'), 'w').write(render(i, data, 'SHA', fake, tk))
        print(f'rendered {len(data["issues"])} bodies to {out}')
        return

    step('checking gh login')
    st = subprocess.run(['gh', 'auth', 'status'], capture_output=True, text=True)
    status = (st.stdout + st.stderr)
    if st.returncode != 0:
        raise SystemExit('gh is not logged in. Run: gh auth login')
    scopes = status.split('Token scopes:')[-1].split('\n')[0] if 'Token scopes:' in status else ''
    if scopes and "'project'" not in scopes:
        print('    warning: token has no "project" scope; run `gh auth refresh -h github.com -s project` to add issues to the project', flush=True)

    sha = run(['git', 'rev-parse', 'HEAD']) if no_git else do_git(repo)
    log['sha'] = sha

    step('labels')
    for lb in data['labels']:
        run(['gh', 'label', 'create', lb['name'], '--repo', repo, '--color', lb['color'], '--description', lb['description'], '--force'], check=False)

    step('milestones')
    existing_ms = {m['title']: m['number'] for m in gh_json(['api', f'repos/{repo}/milestones?state=all&per_page=100'])}
    for m in data['milestones']:
        if m['title'] not in existing_ms:
            run(['gh', 'api', '-X', 'POST', f'repos/{repo}/milestones', '-f', f'title={m["title"]}', '-f', f'description={m["description"]}'])
    ms_title = {m['id']: m['title'] for m in data['milestones']}

    step('existing issues')
    existing = {i['title']: i for i in gh_json(['issue', 'list', '--repo', repo, '--state', 'all', '--limit', '1000', '--json', 'number,title,url'])}
    titles_by_key = {i['key']: i['title'] for i in data['issues']}
    nums, urls = {}, {}
    for i in data['issues']:
        if i['title'] in existing:
            nums[i['key']] = existing[i['title']]['number']
            urls[i['key']] = existing[i['title']]['url']

    step('creating issues')
    bodies_dir = os.path.join(ROOT, 'scripts', 'plan', '.bodies')
    os.makedirs(bodies_dir, exist_ok=True)
    for i in data['issues']:
        if i['key'] in nums:
            continue
        body = render(i, data, sha, nums, titles_by_key)
        path = os.path.join(bodies_dir, i['key'] + '.md')
        open(path, 'w').write(body)
        args = ['gh', 'issue', 'create', '--repo', repo, '--title', i['title'], '--body-file', path, '--milestone', ms_title[i['milestone']]]
        for lb in i['labels']:
            args += ['--label', lb]
        url = run(args, retries=3)
        n = int(url.rstrip('/').split('/')[-1])
        nums[i['key']], urls[i['key']] = n, url
        log['issues'][i['key']] = {'number': n, 'url': url}
        save_log()
        print(f'    #{n} {i["title"]}', flush=True)
        time.sleep(1.5)

    step('filling in cross references')
    for i in data['issues']:
        body = render(i, data, sha, nums, titles_by_key)
        path = os.path.join(bodies_dir, i['key'] + '.md')
        open(path, 'w').write(body)
        run(['gh', 'issue', 'edit', str(nums[i['key']]), '--repo', repo, '--body-file', path], retries=3)
        log['issues'].setdefault(i['key'], {'number': nums[i['key']], 'url': urls[i['key']]})
        time.sleep(1.0)

    step('linking sub-issues')
    ids = {}
    for i in data['issues']:
        if not i['parent']:
            continue
        cid = run(['gh', 'api', f'repos/{repo}/issues/{nums[i["key"]]}', '--jq', '.id'])
        r = run(['gh', 'api', '-X', 'POST', f'repos/{repo}/issues/{nums[i["parent"]]}/sub_issues', '-F', f'sub_issue_id={cid}'],
                check=False, quiet=True)
        log['issues'][i['key']]['sub_issue'] = bool(r)
        time.sleep(0.5)
    save_log()

    step(f'adding issues to project {owner}/{number}')
    added = 0
    for i in data['issues']:
        r = run(['gh', 'project', 'item-add', number, '--owner', owner, '--url', urls[i['key']], '--format', 'json'], check=False, retries=2)
        log['issues'][i['key']]['project'] = bool(r)
        added += 1 if r else 0
        time.sleep(0.5)
    save_log()

    step(f'done: {len(nums)} issues, {added} added to the project')
    print(json.dumps({'pr': log.get('pr'), 'issues': len(nums), 'project_items': added, 'errors': len(log['errors'])}, indent=1))


if __name__ == '__main__':
    try:
        main()
    finally:
        save_log()
