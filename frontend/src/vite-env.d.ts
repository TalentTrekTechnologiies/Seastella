/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** "true" builds a frontend-only demo that answers the API from captured seed data. */
  readonly VITE_DEMO_MODE?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
