'use strict';

const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('iea', {
  // settings
  getSettings: () => ipcRenderer.invoke('settings:get'),
  saveSettings: (patch) => ipcRenderer.invoke('settings:save', patch),
  pickJava: () => ipcRenderer.invoke('dialog:pickJava'),
  pickDir: () => ipcRenderer.invoke('dialog:pickDir'),
  openGameDir: (dir) => ipcRenderer.invoke('dialog:openGameDir', dir),

  // auth
  loginOffline: (username) => ipcRenderer.invoke('auth:offline', username),
  loginMicrosoft: () => ipcRenderer.invoke('auth:microsoft'),
  logout: () => ipcRenderer.invoke('auth:logout'),

  // account switcher
  listAccounts: () => ipcRenderer.invoke('accounts:list'),
  selectAccount: (id) => ipcRenderer.invoke('accounts:select', id),
  removeAccount: (id) => ipcRenderer.invoke('accounts:remove', id),

  // player skin images (data URLs)
  skinFace: (uuid) => ipcRenderer.invoke('skin:face', uuid),
  skinBody: (uuid, model) => ipcRenderer.invoke('skin:body', uuid, model),
  skinModel: (uuid) => ipcRenderer.invoke('skin:model', uuid),

  // what's-new + app version + discord toggle
  getNews: () => ipcRenderer.invoke('news:get'),
  appVersion: () => ipcRenderer.invoke('app:version'),
  setDiscord: (on) => ipcRenderer.invoke('discord:set', on),

  // resource packs
  importPack: () => ipcRenderer.invoke('packs:import'),
  listPacks: () => ipcRenderer.invoke('packs:list'),
  openPacksDir: () => ipcRenderer.invoke('packs:open'),
  removePack: (name) => ipcRenderer.invoke('packs:remove', name),

  // launch
  launch: (opts) => ipcRenderer.invoke('game:launch', opts || {}),
  stop: () => ipcRenderer.invoke('game:stop'),

  // events from main -> renderer
  on: (event, cb) => {
    const channel = 'game:' + event;
    const listener = (_e, payload) => cb(payload);
    ipcRenderer.on(channel, listener);
    return () => ipcRenderer.removeListener(channel, listener);
  },
  onAuthPrompt: (cb) => {
    const listener = (_e, payload) => cb(payload);
    ipcRenderer.on('auth:prompt', listener);
    return () => ipcRenderer.removeListener('auth:prompt', listener);
  },
});
