/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** "true" builds a frontend-only demo that answers the API from captured seed data. */
  readonly VITE_DEMO_MODE?: string;
  /** Backend origin used by the live app, e.g. https://seastella.onrender.com */
  readonly VITE_API_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
