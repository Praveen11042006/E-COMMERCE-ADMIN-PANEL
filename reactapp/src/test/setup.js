import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach, vi } from 'vitest';

afterEach(() => {
  cleanup();
  localStorage.clear();
});

// Components use confirm for destructive actions; tests can override this per case.
vi.stubGlobal('confirm', vi.fn(() => true));
