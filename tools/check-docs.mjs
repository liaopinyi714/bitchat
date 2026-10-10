import { readFileSync, readdirSync, existsSync } from "node:fs";
import { dirname, resolve, relative } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const files = ["README.md", "CONTRIBUTING.md", "SECURITY.md", "CHANGELOG.md", "UPSTREAM.md",
    ...readdirSync(resolve(root, "docs")).filter((name) => name.endsWith(".md")).map((name) => `docs/${name}`)];
const anchors = new Map();

function headings(path) {
    if (anchors.has(path)) return anchors.get(path);
    const counts = new Map();
    const result = new Set();
    let fenced = false;
    for (const line of readFileSync(path, "utf8").split(/\r?\n/)) {
        if (/^\s*```/.test(line)) { fenced = !fenced; continue; }
        if (fenced) continue;
        const heading = /^#{1,6}\s+(.+?)\s*#*\s*$/.exec(line)?.[1];
        if (!heading) continue;
        const base = heading.toLowerCase().replace(/[^\p{L}\p{N}_\- ]/gu, "").replaceAll(" ", "-");
        const count = counts.get(base) ?? 0;
        counts.set(base, count + 1);
        result.add(count ? `${base}-${count}` : base);
    }
    anchors.set(path, result);
    return result;
}

const errors = [];
for (const file of files) {
    const source = resolve(root, file);
    for (const match of readFileSync(source, "utf8").matchAll(/\[[^\]\n]+\]\(([^)\n]+)\)/g)) {
        const target = match[1];
        if (/^[a-z][a-z\d+.-]*:/i.test(target)) continue;
        const [name, anchor] = target.split("#", 2);
        const path = name ? resolve(dirname(source), decodeURIComponent(name)) : source;
        if (!existsSync(path)) errors.push(`${file}: missing ${target}`);
        else if (anchor && path.endsWith(".md") && !headings(path).has(decodeURIComponent(anchor))) {
            errors.push(`${file}: missing heading in ${relative(root, path)}#${anchor}`);
        }
    }
}
if (errors.length) {
    for (const error of errors) process.stderr.write(`${error}\n`);
    process.exitCode = 1;
} else process.stdout.write(`Documentation links checked: ${files.length} files\n`);
