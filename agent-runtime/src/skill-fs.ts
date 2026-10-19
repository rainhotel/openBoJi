// Read-only Android asset/user skill snapshot. No device filesystem access in JS.
export let files: Record<string, string> = {};
export function setFiles(value: Record<string, string>) { files = value; }
export function existsSync(path: string) { return path in files || Object.keys(files).some(p => p.startsWith(path + '/')); }
export function readFileSync(path: string) { if (!(path in files)) throw Error('File unavailable'); return files[path]; }
export function statSync(path: string) { return { isFile: () => path in files, isDirectory: () => !(path in files) && existsSync(path) }; }
export function readdirSync(dir: string) {
  return [...new Set(Object.keys(files).filter(p => p.startsWith(dir + '/')).map(p => p.slice(dir.length + 1).split('/')[0]))]
    .map(name => ({ name, ...statSync(dir + '/' + name), isSymbolicLink: () => false }));
}
