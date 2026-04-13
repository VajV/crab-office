"use client";

import type { SandboxFileEntry } from "@/types";

interface FilesPanelProps {
  files: SandboxFileEntry[];
  selectedPath: string | null;
  fileContent: string;
  loading: boolean;
  error: string | null;
  onSelect: (path: string) => void;
  onRefresh: () => void;
}

export default function FilesPanel({
  files,
  selectedPath,
  fileContent,
  loading,
  error,
  onSelect,
  onRefresh,
}: FilesPanelProps) {
  if (error) {
    return <p className="text-red-400 text-sm p-3">{error}</p>;
  }

  if (files.length === 0) {
    return (
      <div className="p-3 text-sm text-gray-500 flex items-center justify-between">
        <span>Песочница пуста</span>
        <button onClick={onRefresh} className="text-xs text-orange-400 hover:underline">
          Обновить
        </button>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-2 p-2">
      <div className="flex items-center justify-between px-1">
        <span className="text-xs text-gray-500">{files.length} файл(ов)</span>
        <button onClick={onRefresh} className="text-xs text-orange-400 hover:underline">
          Обновить
        </button>
      </div>

      <div className="flex gap-2 max-h-64">
        {/* File list */}
        <ul className="w-1/3 overflow-y-auto space-y-0.5 text-xs border-r border-gray-700 pr-2">
          {files.map((f) => (
            <li key={f.path}>
              <button
                onClick={() => onSelect(f.path)}
                className={`w-full text-left px-1.5 py-1 rounded truncate ${
                  selectedPath === f.path
                    ? "bg-orange-900/40 text-orange-300"
                    : "text-gray-300 hover:bg-gray-800"
                }`}
              >
                📄 {f.path}
              </button>
            </li>
          ))}
        </ul>

        {/* File content */}
        <div className="w-2/3 overflow-auto">
          {loading ? (
            <p className="text-xs text-gray-500 p-2">Загрузка…</p>
          ) : selectedPath ? (
            <pre className="text-xs text-gray-200 bg-gray-950 p-2 rounded whitespace-pre-wrap break-words">
              {fileContent}
            </pre>
          ) : (
            <p className="text-xs text-gray-500 p-2">Выберите файл</p>
          )}
        </div>
      </div>
    </div>
  );
}
