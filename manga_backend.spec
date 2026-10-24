# -*- mode: python ; coding: utf-8 -*-
from PyInstaller.utils.hooks import collect_dynamic_libs

datas = [('static', 'static')]
binaries = []
hiddenimports = []
# Only faiss's core module and DLLs. collect_all() would also pull faiss.contrib, which
# drags in torch / scipy / transformers (~500 MB) that the app never uses.
binaries += collect_dynamic_libs('faiss')
hiddenimports += ['faiss', 'faiss.loader', 'faiss.swigfaiss']


a = Analysis(
    ['run.py'],
    pathex=[],
    binaries=binaries,
    datas=datas,
    hiddenimports=hiddenimports,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=['numba', 'llvmlite', 'onnxruntime', 'tkinter', 'matplotlib',
              'torch', 'torchvision', 'transformers', 'tokenizers', 'scipy', 'sklearn', 'pandas',
              'faiss.contrib', 'yt_dlp', 'curl_cffi', 'IPython', 'jupyter_client', 'notebook'],
    noarchive=False,
    optimize=0,
)
pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name='manga_backend',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    console=False,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)
coll = COLLECT(
    exe,
    a.binaries,
    a.datas,
    strip=False,
    upx=False,
    upx_exclude=[],
    name='manga_backend',
)
