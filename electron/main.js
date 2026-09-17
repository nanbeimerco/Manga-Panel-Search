const { app, BrowserWindow, ipcMain, dialog } = require('electron');
const path = require('path');
const http = require('http');
const { spawn, exec } = require('child_process');

const BACKEND_PORT = 8088;
const SERVER_URL = `http://127.0.0.1:${BACKEND_PORT}`;

let mainWindow = null;
let backendProcess = null;

/**
 * Check if the Python backend is already running on BACKEND_PORT
 */
function checkBackendReady() {
  return new Promise((resolve) => {
    const req = http.get(`${SERVER_URL}/api/status`, (res) => {
      if (res.statusCode === 200) {
        resolve(true);
      } else {
        resolve(false);
      }
    });
    req.on('error', () => {
      resolve(false);
    });
    req.setTimeout(800, () => {
      req.destroy();
      resolve(false);
    });
  });
}

const fs = require('fs');

/**
 * Start the Python backend process if not already running
 */
async function ensureBackendRunning() {
  const isPackaged = app.isPackaged;

  // In packaged app, clean up any previous orphaned manga_backend process to guarantee latest binary runs
  if (isPackaged && process.platform === 'win32') {
    await new Promise((res) => {
      exec('taskkill /IM manga_backend.exe /F', () => res());
    });
    await new Promise((r) => setTimeout(r, 400));
  } else {
    const isAlreadyRunning = await checkBackendReady();
    if (isAlreadyRunning) {
      console.log(`[Electron] Backend is already running on port ${BACKEND_PORT}.`);
      return true;
    }
  }

  console.log(`[Electron] Starting Python backend on port ${BACKEND_PORT}...`);
  let projectRoot = path.join(__dirname, '..');
  let backendExe = null;

  if (isPackaged) {
    const packagedExe = path.join(process.resourcesPath, 'manga_backend', 'manga_backend.exe');
    if (fs.existsSync(packagedExe)) {
      backendExe = packagedExe;
      projectRoot = path.dirname(packagedExe);
    }
  }

  if (backendExe) {
    console.log(`[Electron] Launching packaged backend: ${backendExe}`);
    backendProcess = spawn(backendExe, ['--port', String(BACKEND_PORT)], {
      cwd: projectRoot,
      shell: false,
      stdio: 'pipe'
    });
  } else {
    const pythonScript = path.join(projectRoot, 'run.py');
    console.log(`[Electron] Launching python script: ${pythonScript}`);
    backendProcess = spawn('python', [pythonScript, '--port', String(BACKEND_PORT)], {
      cwd: projectRoot,
      shell: true,
      stdio: 'pipe'
    });
  }

  backendProcess.stdout.on('data', (data) => {
    console.log(`[Backend stdout] ${data}`);
  });

  backendProcess.stderr.on('data', (data) => {
    console.error(`[Backend stderr] ${data}`);
  });

  backendProcess.on('close', (code) => {
    console.log(`[Backend] Process exited with code ${code}`);
    backendProcess = null;
  });

  // Poll until backend is ready (up to 30 seconds)
  const maxRetries = 60;
  for (let i = 0; i < maxRetries; i++) {
    await new Promise((r) => setTimeout(r, 500));
    const ready = await checkBackendReady();
    if (ready) {
      console.log(`[Electron] Backend is ready on port ${BACKEND_PORT}!`);
      return true;
    }
  }

  console.error('[Electron] Backend failed to start in time.');
  return false;
}

/**
 * Cleanly terminate backend process tree
 */
function killBackend() {
  if (backendProcess && backendProcess.pid) {
    console.log(`[Electron] Terminating backend process tree (PID ${backendProcess.pid})...`);
    if (process.platform === 'win32') {
      exec(`taskkill /pid ${backendProcess.pid} /T /F`, (err) => {
        if (err) console.error('[Electron] taskkill error:', err.message);
      });
    } else {
      backendProcess.kill('SIGTERM');
    }
    backendProcess = null;
  }
}

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1280,
    height: 860,
    minWidth: 900,
    minHeight: 600,
    frame: false, // Frameless window: eliminates ugly default OS title bar
    titleBarStyle: 'hidden',
    backgroundColor: '#141218',
    show: false,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      nodeIntegration: false,
      contextIsolation: true,
      webSecurity: false
    }
  });

  // Window control event listeners
  mainWindow.on('maximize', () => {
    if (mainWindow) mainWindow.webContents.send('window-maximize-changed', true);
  });

  mainWindow.on('unmaximize', () => {
    if (mainWindow) mainWindow.webContents.send('window-maximize-changed', false);
  });

  mainWindow.once('ready-to-show', () => {
    mainWindow.show();
  });

  // Clear cache to always ensure latest HTML/JS/CSS assets
  mainWindow.webContents.session.clearCache();

  // Load backend URL
  mainWindow.loadURL(SERVER_URL);
}

// Window IPC handlers
ipcMain.on('window-minimize', () => {
  if (mainWindow) mainWindow.minimize();
});

ipcMain.on('window-toggle-maximize', () => {
  if (mainWindow) {
    if (mainWindow.isMaximized()) {
      mainWindow.unmaximize();
    } else {
      mainWindow.maximize();
    }
  }
});

ipcMain.on('window-close', () => {
  if (mainWindow) mainWindow.close();
});

// File / Folder Selection Dialog IPC Handlers
ipcMain.handle('dialog-select-folder', async () => {
  if (!mainWindow) return null;
  const result = await dialog.showOpenDialog(mainWindow, {
    title: '漫画フォルダを選択',
    properties: ['openDirectory']
  });
  if (!result.canceled && result.filePaths.length > 0) {
    return result.filePaths[0];
  }
  return null;
});

ipcMain.handle('dialog-select-files', async () => {
  if (!mainWindow) return [];
  const result = await dialog.showOpenDialog(mainWindow, {
    title: '漫画アーカイブまたは画像ファイルを選択',
    properties: ['openFile', 'multiSelections'],
    filters: [
      { name: '漫画・アーカイブ・画像', extensions: ['cbz', 'cbr', 'cbt', 'cb7', 'zip', 'rar', 'tar', '7z', 'jpg', 'jpeg', 'png', 'webp'] },
      { name: 'すべてのファイル', extensions: ['*'] }
    ]
  });
  if (!result.canceled && result.filePaths.length > 0) {
    return result.filePaths;
  }
  return [];
});

// App Lifecycle
app.whenReady().then(async () => {
  await ensureBackendRunning();
  createWindow();

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createWindow();
    }
  });
});

app.on('before-quit', () => {
  killBackend();
});

app.on('window-all-closed', () => {
  killBackend();
  if (process.platform !== 'darwin') {
    app.quit();
  }
});
