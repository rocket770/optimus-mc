/* Load matching local editor assets without reusing a cached graph from another update. */
'use strict';
(() => {
  const build = 'workshop-r4',
    stamp = Date.now().toString(36),
    version = document.getElementById('editor-version');
  window.OPTIMUS_EDITOR_BUILD = build;
  if (version) version.textContent = 'Editor r4';
  function fail(message) {
    document.getElementById('status').textContent = message;
    document.getElementById('status').style.color = '#ffba9b';
    document.querySelectorAll('button, input, select').forEach((control) => {
      control.disabled = true;
    });
  }
  function load(name) {
    return new Promise((resolve, reject) => {
      const script = document.createElement('script');
      script.src = name + '?build=' + build + '&load=' + stamp;
      script.onload = resolve;
      script.onerror = () =>
        reject(
          new Error(
            'Could not load ' + name + '. Open the maintained editor folder.'
          )
        );
      document.head.append(script);
    });
  }
  (async () => {
    await load('pit-map.js');
    if (
      window.PIT_MAP?.editorBuild !== build ||
      !window.PIT_MAP.momentumChains?.length ||
      !window.PIT_MAP.precisionJumps?.length
    )
      throw new Error(
        'Editor map is from a different update. Reopen the maintained editor; this copy cannot check the advanced jumps.'
      );
    await load('graph.js');
    if (window.PitGraph?.build !== build)
      throw new Error(
        'Editor files are from different updates. Reopen the maintained editor.'
      );
    await load('vendor/fflate.js');
    await load('map/nbt.js');
    await load('map/geometry.js');
    await load('map/import.js');
    await load('app.js');
    if (window.OPTIMUS_APP_BUILD !== build)
      throw new Error(
        'Editor app is from a different update. Reopen the maintained editor.'
      );
  })().catch((error) => fail(error.message));
})();