// Android host adapter: virtual POSIX paths; no symlinks or shell resolution.
import { resolve } from 'path-browserify';
export const canonicalizePath = (path: string) => resolve(path);
export const resolvePath = (path: string, cwd: string) => resolve(cwd, path);
