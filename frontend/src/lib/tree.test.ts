import { describe, expect, it } from 'vitest';
import type { CallTreeNode } from '../api/types.ts';
import { ancestorIds, loadedKeys, nodeAt, withChildren } from './tree.ts';

const n = (nodeId: string, children: CallTreeNode[] | null = null): CallTreeNode => ({
  nodeId, kind: 'METHOD', name: nodeId, service: 's', visibility: 'PUBLIC', isStatic: false, file: null,
  startLine: null, endLine: null, call: null, recursive: false, hasChildren: children === null || children.length > 0,
  children, details: {},
});

describe('call tree helpers', () => {
  const root = n('root', [n('a', [n('a1')]), n('b')]);

  it('finds nodes and ancestors by path', () => {
    expect(nodeAt(root, [0, 0]).nodeId).toBe('a1');
    expect(ancestorIds(root, [])).toEqual([]);
    expect(ancestorIds(root, [1])).toEqual(['root']);
    expect(ancestorIds(root, [0, 0])).toEqual(['root', 'a']);
  });

  it('replaces children immutably', () => {
    const updated = withChildren(root, [1], [n('b1', [])]);
    expect(nodeAt(updated, [1, 0]).nodeId).toBe('b1');
    expect(root.children![1].children).toBeNull();
    expect(updated.children![0]).toBe(root.children![0]);
  });

  it('lists keys of loaded subtrees', () => {
    expect(loadedKeys(root)).toEqual(['', '0']);
  });
});
