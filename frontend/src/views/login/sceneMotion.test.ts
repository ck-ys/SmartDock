import { describe, expect, it } from 'vitest';
import { resolveSceneMode } from './sceneMotion';

describe('login scene motion preference', () => {
  it('animates by default on a visible desktop', () => {
    expect(resolveSceneMode({ choice: null })).toBe('dynamic');
  });

  it('keeps a saved static selection still', () => {
    expect(resolveSceneMode({ choice: 'static' })).toBe('static');
  });

  it.each(['static', 'dynamic', null])('always respects reduced motion for %s', (choice) => {
    expect(resolveSceneMode({ choice, reduced: true })).toBe('static');
  });

  it('suspends decoration when the page is hidden', () => {
    expect(resolveSceneMode({ choice: 'dynamic', hidden: true })).toBe('static');
  });

  it('keeps compact devices still even with a desktop preference', () => {
    expect(resolveSceneMode({ choice: 'dynamic', compact: true })).toBe('static');
  });

  it('ignores invalid persisted values instead of losing the scene', () => {
    expect(resolveSceneMode({ choice: 'broken-storage-value' })).toBe('dynamic');
  });
});
