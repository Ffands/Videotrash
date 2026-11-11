import React, { useState } from 'react';
import {
  Check,
  Copy,
  Cpu,
  Download,
  FolderArchive,
  Smartphone,
} from 'lucide-react';

import settingsGradleRaw from '../settings.gradle.kts?raw';
import gradlePropertiesRaw from '../gradle.properties?raw';
import rootBuildGradleRaw from '../build.gradle.kts?raw';
import appBuildGradleRaw from '../app/build.gradle.kts?raw';
import proguardRulesRaw from '../app/proguard-rules.pro?raw';
import androidManifestRaw from '../app/src/main/AndroidManifest.xml?raw';
import githubWorkflowRaw from '../.github/workflows/build-apk.yml?raw';

import powerHelperRaw from '../app/src/main/java/forge/media/pocket/service/PowerOptimizationHelper.kt?raw';
import pipelineServiceRaw from '../app/src/main/java/forge/media/pocket/service/PipelineForegroundService.kt?raw';
import faceCenteringRaw from '../app/src/main/java/forge/media/pocket/vision/FaceCenteringHelper.kt?raw';
import clutterFilterRaw from '../app/src/main/java/forge/media/pocket/clutter/ClutterEntropyFilter.kt?raw';
import topicHunterRaw from '../app/src/main/java/forge/media/pocket/discovery/TopicHunterEngine.kt?raw';
import subtitleTransformerRaw from '../app/src/main/java/forge/media/pocket/subtitles/SubtitleAssTransformer.kt?raw';
import ffmpegEngineRaw from '../app/src/main/java/forge/media/pocket/compositor/FFmpegHardwareEngine.kt?raw';
import geminiClientRaw from '../app/src/main/java/forge/media/pocket/telegram/GeminiMetadataClient.kt?raw';
import telegramWorkerRaw from '../app/src/main/java/forge/media/pocket/telegram/TelegramBotWorker.kt?raw';
import nodeConfigStoreRaw from '../app/src/main/java/forge/media/pocket/config/NodeConfigStore.kt?raw';
import mainActivityRaw from '../app/src/main/java/forge/media/pocket/ui/MainActivity.kt?raw';

interface ProjectFile {
  step: 1 | 2 | 3 | 4 | 5 | 6;
  path: string;
  language: string;
  description: string;
  content: string;
}

const PROJECT_FILES: ProjectFile[] = [
  {
    step: 1,
    path: 'settings.gradle.kts',
    language: 'kotlin',
    description: 'Root Gradle settings with Google, MavenCentral, JitPack (4A), and flatDir app/libs (1B).',
    content: settingsGradleRaw,
  },
  {
    step: 1,
    path: 'gradle.properties',
    language: 'properties',
    description: 'JVM heap, AndroidX, and non-transitive R-class settings for headless GitHub Actions CI.',
    content: gradlePropertiesRaw,
  },
  {
    step: 1,
    path: 'build.gradle.kts',
    language: 'kotlin',
    description: 'Top-level Gradle configuration (AGP 8.7.2, Kotlin 2.0.21, Serialization).',
    content: rootBuildGradleRaw,
  },
  {
    step: 1,
    path: 'app/build.gradle.kts',
    language: 'kotlin',
    description: 'Hybrid 6C config: reads optional GitHub Secrets into BuildConfig defaults + on-device override.',
    content: appBuildGradleRaw,
  },
  {
    step: 1,
    path: 'app/proguard-rules.pro',
    language: 'properties',
    description: 'Keep rules for FFmpegKit JNI, YoutubeDL-Android, Google ML Kit, and Kotlinx Serialization.',
    content: proguardRulesRaw,
  },
  {
    step: 1,
    path: 'app/src/main/AndroidManifest.xml',
    language: 'xml',
    description: 'Android 14 (API 34) manifest registering MainActivity and PipelineForegroundService.',
    content: androidManifestRaw,
  },
  {
    step: 1,
    path: '.github/workflows/build-apk.yml',
    language: 'yaml',
    description: 'GitHub Actions workflow: triggers on any push, downloads verified FFmpegKit AAR, and builds ARM64 APK.',
    content: githubWorkflowRaw,
  },
  {
    step: 2,
    path: 'app/src/main/java/forge/media/pocket/service/PowerOptimizationHelper.kt',
    language: 'kotlin',
    description: 'PARTIAL_WAKE_LOCK, Low-Latency WifiLock, OxygenOS intents, and Screen-Off-Only Silent Audio Loop.',
    content: powerHelperRaw,
  },
  {
    step: 2,
    path: 'app/src/main/java/forge/media/pocket/service/PipelineForegroundService.kt',
    language: 'kotlin',
    description: 'Android 14 Foreground LifecycleService running native yt-dlp/Aria2c and TelegramBotWorker.',
    content: pipelineServiceRaw,
  },
  {
    step: 3,
    path: 'app/src/main/java/forge/media/pocket/vision/FaceCenteringHelper.kt',
    language: 'kotlin',
    description: '1 FPS FFmpeg thumbnail batch + Google ML Kit Face Detection with adaptive multi-speaker crop.',
    content: faceCenteringRaw,
  },
  {
    step: 3,
    path: 'app/src/main/java/forge/media/pocket/clutter/ClutterEntropyFilter.kt',
    language: 'kotlin',
    description: 'Dual-sided entropy filter rejecting hard scene cuts (>0.40) and static freezes (>2.0s).',
    content: clutterFilterRaw,
  },
  {
    step: 3,
    path: 'app/src/main/java/forge/media/pocket/discovery/TopicHunterEngine.kt',
    language: 'kotlin',
    description: 'Autonomous Tag & Topic Hunter across YouTube, Reddit, and Pikabu with sorting and time filters.',
    content: topicHunterRaw,
  },
  {
    step: 4,
    path: 'app/src/main/java/forge/media/pocket/subtitles/SubtitleAssTransformer.kt',
    language: 'kotlin',
    description: 'VTT-to-ASS karaoke converter with movable vertical position and Gemini 2.5 Flash audio fallback.',
    content: subtitleTransformerRaw,
  },
  {
    step: 4,
    path: 'app/src/main/java/forge/media/pocket/compositor/FFmpegHardwareEngine.kt',
    language: 'kotlin',
    description: 'Qualcomm h264_mediacodec hardware compositor supporting 4 assembly modes, ad layers, and anti-detect DSP.',
    content: ffmpegEngineRaw,
  },
  {
    step: 5,
    path: 'app/src/main/java/forge/media/pocket/telegram/GeminiMetadataClient.kt',
    language: 'kotlin',
    description: 'Generates 3 viral titles, hashtags, and source/anime attribution comment via Gemini 2.5 Flash.',
    content: geminiClientRaw,
  },
  {
    step: 5,
    path: 'app/src/main/java/forge/media/pocket/telegram/TelegramBotWorker.kt',
    language: 'kotlin',
    description: 'Full Telegram Bot controller with interactive layer matrix, tag hunter UI, and raw asset pack export.',
    content: telegramWorkerRaw,
  },
  {
    step: 6,
    path: 'app/src/main/java/forge/media/pocket/config/NodeConfigStore.kt',
    language: 'kotlin',
    description: 'Hybrid 6C storage reading BuildConfig defaults from GitHub Secrets with live on-device override.',
    content: nodeConfigStoreRaw,
  },
  {
    step: 6,
    path: 'app/src/main/java/forge/media/pocket/ui/MainActivity.kt',
    language: 'kotlin',
    description: 'Dark-themed on-device control panel for OnePlus: live token setup, midroll MP4 picker, and service controls.',
    content: mainActivityRaw,
  },
];

export default function App() {
  const [selectedStepFilter, setSelectedStepFilter] = useState<'all' | 1 | 2 | 3 | 4 | 5 | 6>('all');
  const [selectedFilePath, setSelectedFilePath] = useState<string>(
    '.github/workflows/build-apk.yml'
  );
  const [copiedPath, setCopiedPath] = useState<string | null>(null);

  const filteredFiles = PROJECT_FILES.filter((f) =>
    selectedStepFilter === 'all' ? true : f.step === selectedStepFilter
  );

  const currentFile =
    PROJECT_FILES.find((f) => f.path === selectedFilePath) || filteredFiles[0];

  const handleCopy = (content: string, path: string) => {
    navigator.clipboard.writeText(content);
    setCopiedPath(path);
    setTimeout(() => setCopiedPath(null), 2000);
  };

  const handleDownloadFile = (file: ProjectFile) => {
    const blob = new Blob([file.content], { type: 'text/plain;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = file.path.split('/').pop() || 'file.kt';
    a.click();
    URL.revokeObjectURL(url);
  };

  const handleDownloadSetupScript = () => {
    const lines: string[] = [
      '#!/usr/bin/env bash',
      'set -e',
      'echo "Generating Complete Pocket Media Forge Android 14 Project (18 files)..."',
    ];
    for (const file of PROJECT_FILES) {
      const dir = file.path.includes('/')
        ? file.path.substring(0, file.path.lastIndexOf('/'))
        : '';
      if (dir) {
        lines.push(`mkdir -p "${dir}"`);
      }
      lines.push(`cat << 'EOF_FORGE_FILE' > "${file.path}"`);
      lines.push(file.content);
      lines.push('EOF_FORGE_FILE');
    }
    lines.push('echo "All 18 files created! Push to GitHub to build your APK via GitHub Actions."');

    const blob = new Blob([lines.join('\n')], { type: 'text/x-shellscript;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'generate_pocket_media_forge.sh';
    a.click();
    URL.revokeObjectURL(url);
  };

  return (
    <div className="min-h-screen bg-zinc-950 text-zinc-100 font-sans selection:bg-amber-500/30">
      <header className="border-b border-zinc-800 bg-zinc-900/70 backdrop-blur sticky top-0 z-20">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 py-4 flex flex-wrap items-center justify-between gap-4">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-lg bg-emerald-500/10 border border-emerald-500/30 flex items-center justify-center text-emerald-400">
              <Cpu className="w-5 h-5" />
            </div>
            <div>
              <div className="flex items-center gap-2">
                <h1 className="text-lg font-bold tracking-tight text-white">
                  POCKET MEDIA FORGE
                </h1>
                <span className="text-xs font-mono uppercase px-2 py-0.5 rounded bg-emerald-500/20 text-emerald-300 border border-emerald-500/40">
                  18 Real Kotlin & Gradle Files on Disk
                </span>
              </div>
              <p className="text-xs text-zinc-400">
                Real Repository Structure • .github/workflows/build-apk.yml Active • Snapdragon ARM64
              </p>
            </div>
          </div>

          <div className="flex items-center gap-2">
            <button
              onClick={handleDownloadSetupScript}
              className="px-4 py-2 rounded-lg bg-emerald-500 hover:bg-emerald-400 text-zinc-950 text-xs font-bold transition flex items-center gap-2 shadow-lg shadow-emerald-500/10"
            >
              <FolderArchive className="w-4 h-4" />
              Скачать весь проект одним скриптом (.sh)
            </button>
          </div>
        </div>
      </header>

      <main className="max-w-7xl mx-auto px-4 sm:px-6 py-6">
        <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
          <div className="lg:col-span-4 space-y-4">
            <div className="bg-zinc-900 border border-zinc-800 rounded-xl p-4">
              <div className="flex items-center justify-between mb-3">
                <span className="text-xs font-mono uppercase tracking-wider text-zinc-400">
                  Реальные файлы в корне репо (18)
                </span>
                <div className="flex flex-wrap gap-1">
                  {(['all', 1, 2, 3, 4, 5, 6] as const).map((stepFilter) => (
                    <button
                      key={String(stepFilter)}
                      onClick={() => setSelectedStepFilter(stepFilter)}
                      className={`px-2 py-0.5 rounded text-xs font-mono transition ${
                        selectedStepFilter === stepFilter
                          ? 'bg-amber-500 text-zinc-950 font-bold'
                          : 'bg-zinc-800 text-zinc-400 hover:text-zinc-200'
                      }`}
                    >
                      {stepFilter === 'all' ? 'Все' : `S${stepFilter}`}
                    </button>
                  ))}
                </div>
              </div>

              <div className="space-y-1.5 max-h-[55vh] overflow-y-auto pr-1">
                {filteredFiles.map((file) => (
                  <button
                    key={file.path}
                    onClick={() => setSelectedFilePath(file.path)}
                    className={`w-full text-left px-3 py-2 rounded-lg text-xs font-mono transition flex items-center justify-between ${
                      currentFile.path === file.path
                        ? 'bg-amber-500/15 text-amber-300 border border-amber-500/30'
                        : 'text-zinc-300 hover:bg-zinc-800/70 border border-transparent'
                    }`}
                  >
                    <div className="truncate pr-2">
                      <span className="text-[10px] text-zinc-500 mr-1.5">[S{file.step}]</span>
                      {file.path}
                    </div>
                    <span className="text-[10px] px-1.5 py-0.5 rounded bg-zinc-800 text-zinc-400 shrink-0">
                      {file.language}
                    </span>
                  </button>
                ))}
              </div>
            </div>

            <div className="bg-zinc-900/70 border border-emerald-500/25 rounded-xl p-4 space-y-2">
              <div className="flex items-center gap-2 text-emerald-400 text-xs font-semibold">
                <Smartphone className="w-4 h-4 shrink-0" />
                <span>Репозиторий исправлен (Kotlin + GitHub Actions)</span>
              </div>
              <p className="text-xs text-zinc-300 leading-relaxed">
                Все 18 файлов записаны напрямую в корень файловой системы воркспейса (включая{' '}
                <code className="text-amber-300">.github/workflows/build-apk.yml</code> и{' '}
                <code className="text-amber-300">app/src/main/java/...</code>), а веб-обёртка скрыта
                через <code className="text-amber-300">.gitattributes</code>.
              </p>
            </div>
          </div>

          <div className="lg:col-span-8">
            <div className="bg-zinc-900 border border-zinc-800 rounded-xl overflow-hidden">
              <div className="px-4 py-3 border-b border-zinc-800 flex flex-wrap items-center justify-between gap-2 bg-zinc-900/90">
                <div>
                  <div className="font-mono text-sm text-amber-300 font-semibold">
                    {currentFile.path}
                  </div>
                  <p className="text-xs text-zinc-400 mt-0.5">{currentFile.description}</p>
                </div>
                <div className="flex items-center gap-2">
                  <button
                    onClick={() => handleCopy(currentFile.content, currentFile.path)}
                    className="px-3 py-1.5 rounded-lg bg-zinc-800 hover:bg-zinc-700 text-xs font-medium text-zinc-200 flex items-center gap-1.5 transition"
                  >
                    {copiedPath === currentFile.path ? (
                      <>
                        <Check className="w-3.5 h-3.5 text-emerald-400" />
                        Скопировано
                      </>
                    ) : (
                      <>
                        <Copy className="w-3.5 h-3.5" />
                        Копировать
                      </>
                    )}
                  </button>
                  <button
                    onClick={() => handleDownloadFile(currentFile)}
                    className="px-3 py-1.5 rounded-lg bg-zinc-800 hover:bg-zinc-700 text-xs font-medium text-zinc-200 flex items-center gap-1.5 transition"
                  >
                    <Download className="w-3.5 h-3.5" />
                    Скачать файл
                  </button>
                </div>
              </div>
              <pre className="p-4 text-xs font-mono text-zinc-200 overflow-x-auto leading-relaxed max-h-[74vh]">
                <code>{currentFile.content}</code>
              </pre>
            </div>
          </div>
        </div>
      </main>
    </div>
  );
}
