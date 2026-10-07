import type { CallTreeNode } from '../api/types.ts';

/** Ağaçtaki konum: kökten itibaren çocuk indeksleri. Aynı metot ağaçta birden çok yerde olabildiği için id yetmez. */
export type TreePath = number[];

export const pathKey = (path: TreePath) => path.join('.');

export function nodeAt(root: CallTreeNode, path: TreePath): CallTreeNode {
  return path.reduce((node, i) => node.children![i], root);
}

/** Kökten (hariç) düğüme kadar olan atalar ve düğüm; özyineleme tespiti için backend'e gönderilir. */
export function ancestorIds(root: CallTreeNode, path: TreePath): string[] {
  const ids = [root.nodeId];
  let node = root;
  for (const i of path.slice(0, -1)) {
    node = node.children![i];
    ids.push(node.nodeId);
  }
  return path.length === 0 ? [] : ids;
}

/** Değişmez güncelleme: {@code path}'teki düğümün çocuklarını değiştirir. */
export function withChildren(root: CallTreeNode, path: TreePath, children: CallTreeNode[]): CallTreeNode {
  if (path.length === 0) return { ...root, children };
  const [head, ...rest] = path;
  const kids = root.children!.slice();
  kids[head] = withChildren(kids[head], rest, children);
  return { ...root, children: kids };
}

/** Yüklenmiş (çocukları gelmiş) tüm düğümlerin anahtarları; "hepsini aç" için. */
export function loadedKeys(root: CallTreeNode, path: TreePath = [], out: string[] = []): string[] {
  if (root.children && root.children.length > 0) {
    out.push(pathKey(path));
    root.children.forEach((c, i) => loadedKeys(c, [...path, i], out));
  }
  return out;
}
