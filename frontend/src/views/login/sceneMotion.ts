export type SceneMode = 'static' | 'dynamic';

interface SceneMotionOptions {
  choice?: string | null;
  reduced?: boolean;
  hidden?: boolean;
  compact?: boolean;
}

export function resolveSceneMode({ choice, reduced, hidden, compact }: SceneMotionOptions): SceneMode {
  return choice === 'static' || reduced || hidden || compact ? 'static' : 'dynamic';
}
