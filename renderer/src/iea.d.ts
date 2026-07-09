// The preload bridge (src/preload/preload.js) exposed as window.iea.
export {};
declare global {
  interface Window {
    iea: any;
  }
}
