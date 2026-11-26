// GitHub 올리기 — ml/registry/<model> 만 커밋해서 push (다른 작업 중인 파일은 건드리지 않는다).
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import fs from 'node:fs';
import path from 'node:path';
import { REPO, REGISTRY } from './store.js';

const execFileP = promisify(execFile);

async function git(args) {
  try {
    const { stdout } = await execFileP('git', args, { cwd: REPO, maxBuffer: 20 * 1024 * 1024, windowsHide: true });
    return stdout;
  } catch (e) {
    const msg = (e.stderr || e.message || '').toString().trim().split('\n').slice(-3).join(' ');
    throw Object.assign(new Error(`git ${args[0]} 실패: ${msg}`), { status: 409 });
  }
}

const rel = (id) => path.relative(REPO, path.join(REGISTRY, id)).split(path.sep).join('/');

export async function branch() {
  return (await git(['rev-parse', '--abbrev-ref', 'HEAD'])).trim();
}

/** 모델 폴더의 GitHub 상태 — 커밋 안 된 변경 수, 마지막 커밋, push 안 된 커밋 수 */
export async function status(ids) {
  const br = await branch();
  const out = {};
  for (const id of ids) {
    const p = rel(id);
    const porcelain = (await git(['status', '--porcelain', '--untracked-files=all', '--', p])).trim();
    const last = (await git(['log', '-1', '--format=%h|%cs|%s', '--', p])).trim();
    let unpushed = 0;
    try { unpushed = Number((await git(['rev-list', '--count', `origin/${br}..HEAD`, '--', p])).trim()) || 0; } catch { /* 원격 브랜치 없음 */ }
    const [hash, date, subject] = last ? last.split('|') : [];
    out[id] = { changed: porcelain ? porcelain.split('\n').length : 0, lastCommit: last ? { hash, date, subject } : null, unpushed };
  }
  return { branch: br, models: out };
}

/**
 * 모델 폴더들을 커밋하고 push. 그 폴더 밖의 staged/수정 파일은 커밋에 넣지 않는다.
 * identity: { name, email } — 비어 있으면 git 설정을 따른다.
 */
export async function publish(ids, message, identity = {}) {
  const paths = ids.map(rel).filter((p) => fs.existsSync(path.join(REPO, p)));
  if (!paths.length) throw Object.assign(new Error('올릴 모델 폴더가 없습니다'), { status: 400 });
  await git(['add', '-A', '--', ...paths]);
  const staged = (await git(['diff', '--cached', '--name-only', '--', ...paths])).trim();
  let commit = null;
  if (staged) {
    const who = identity.name && identity.email ? ['-c', `user.name=${identity.name}`, '-c', `user.email=${identity.email}`] : [];
    await git([...who, 'commit', '-m', message, '--', ...paths]);
    commit = (await git(['rev-parse', '--short', 'HEAD'])).trim();
  }
  const br = await branch();
  await git(['push', 'origin', `HEAD:${br}`]);
  return { commit, files: staged ? staged.split('\n').length : 0, branch: br, pushed: true };
}
