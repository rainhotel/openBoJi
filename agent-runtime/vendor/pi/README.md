Upstream: https://github.com/earendil-works/pi
Commit: 482c1381a00d8d00bc81697e4a980c593db6803e (1.3.0)
License: MIT, see LICENSE.

core/skills.ts, core/source-info.ts, utils/frontmatter.ts and utils/text.ts are
unchanged upstream source. config.ts, utils/paths.ts and src/skill-fs.ts are
Boji platform adapters. Only the skill loader is vendored; the Agent loop and
schema validation use pinned official npm packages, not a fork.
