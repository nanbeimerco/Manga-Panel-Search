const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('electronAPI', {
  minimize: () => ipcRenderer.send('window-minimize'),
  toggleMaximize: () => ipcRenderer.send('window-toggle-maximize'),
  close: () => ipcRenderer.send('window-close'),
  onMaximizeChange: (callback) => {
    ipcRenderer.on('window-maximize-changed', (_event, isMaximized) => {
      callback(isMaximized);
    });
  },
  selectFolder: () => ipcRenderer.invoke('dialog-select-folder'),
  selectFiles: () => ipcRenderer.invoke('dialog-select-files')
});
