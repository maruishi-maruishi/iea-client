import React from 'react';
import { Account } from '../types';
import { Play, Square, Terminal, UserCheck } from 'lucide-react';
import { useT } from '../lang';

interface TabPlayProps {
  currentAccount: Account;
  isLaunching: boolean;
  launchProgress: number;
  launchStatus: string;
  onStartLaunch: () => void;
  onKillLaunch: () => void;
  onNavigateToLogs: () => void;
  faceUrl?: string;
  appVersion?: string;
}

export default function TabPlay({
  currentAccount,
  isLaunching,
  launchProgress,
  launchStatus,
  onStartLaunch,
  onKillLaunch,
  onNavigateToLogs,
  faceUrl,
  appVersion,
}: TabPlayProps) {
  const t = useT();
  return (
    <div className="h-full flex flex-col items-center justify-center gap-7 animate-fadeIn text-center px-4">

      {/* アカウント: 顔 + 名前 */}
      <div className="flex flex-col items-center gap-3">
        <div className="w-20 h-20 rounded-2xl overflow-hidden bg-lime-500/10 border border-lime-500/20 flex items-center justify-center text-lime-400 shadow-[0_0_25px_rgba(163,230,53,0.15)]">
          {faceUrl
            ? <img src={faceUrl} alt="" draggable={false} className="w-full h-full object-cover" style={{ imageRendering: 'pixelated' }} />
            : <UserCheck size={32} />}
        </div>
        <div>
          <div className="text-lg font-bold text-[#e7e9ee]">{currentAccount.username}</div>
          <div className="text-[10px] font-mono text-[#8a8f9c] uppercase tracking-wider mt-1 flex items-center justify-center gap-1.5">
            <span className="w-1.5 h-1.5 rounded-full bg-lime-500" />
            {currentAccount.type} · Minecraft 1.8.9
          </div>
        </div>
      </div>

      {/* 起動 / 停止 */}
      {!isLaunching ? (
        <button
          id="btn-play"
          onClick={() => onStartLaunch()}
          className="group relative overflow-hidden w-[300px] py-4 rounded-xl font-bold text-sm tracking-widest transition-all duration-300 flex items-center justify-center gap-2 bg-gradient-to-r from-lime-400 to-lime-600 hover:from-lime-300 hover:to-lime-500 text-[#0e0f14] active:scale-[0.98] cursor-pointer shadow-[0_8px_30px_rgba(163,230,53,0.25)] hover:shadow-[0_10px_36px_rgba(163,230,53,0.4)] border border-lime-400/20"
        >
          <div className="absolute inset-0 bg-white/20 transform -translate-x-full group-hover:translate-x-full transition-transform duration-1000 ease-out pointer-events-none" />
          <Play size={16} fill="currentColor" className="group-hover:scale-110 transition-transform" />
          {t('LAUNCH MINECRAFT')}
        </button>
      ) : (
        <div className="w-[320px] flex flex-col gap-3">
          {/* 進捗 */}
          <div className="space-y-2">
            <div className="flex justify-between text-[11px] font-mono">
              <span className="text-lime-400 animate-pulse flex items-center gap-1.5 font-bold">
                <Terminal size={12} />
                {launchStatus}
              </span>
              <span className="text-[#e7e9ee] font-bold">{launchProgress}%</span>
            </div>
            <div className="w-full h-2 bg-[#0e0f14] rounded-full overflow-hidden border border-[#262a36]">
              <div
                className="h-full bg-gradient-to-r from-lime-500 to-lime-300 rounded-full transition-all duration-300 ease-out"
                style={{ width: `${launchProgress}%` }}
              />
            </div>
          </div>
          <div className="flex gap-2">
            <button
              onClick={onNavigateToLogs}
              className="flex-1 bg-[#1c1f29] border border-[#262a36] hover:bg-[#262a36] text-[#e7e9ee] py-3 rounded-lg font-bold text-xs tracking-wider transition-all flex items-center justify-center gap-2 cursor-pointer active:scale-[0.98]"
            >
              <Terminal size={14} className="text-lime-400" />
              {t('LIVE HANDSHAKE LOGS')}
            </button>
            <button
              onClick={onKillLaunch}
              className="bg-red-500/10 border border-red-500/30 hover:bg-red-500/20 text-red-400 px-4 rounded-lg transition-all flex items-center justify-center cursor-pointer active:scale-[0.98]"
              title="Kill Process"
            >
              <Square size={16} fill="currentColor" />
            </button>
          </div>
        </div>
      )}

      <p className="text-[10px] font-mono text-[#8a8f9c]/50">v{appVersion || '1.0.2'}</p>
    </div>
  );
}
