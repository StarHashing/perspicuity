# -*- coding: utf-8 -*-
"""给 10 个语言文件注入 FilesTab 新增的 files.* 键。

幂等：已存在同名键则跳过。插入位置固定在 'files.emptyBody' 之后。
"""
import io
import os
import re

BASE = '/root/perspicuity-fork/src/lib/locales'

TRANSLATIONS = {
    'en': [
        ('searchPlaceholder', 'Search bound folders and recent files'),
        ('searchClear', 'Clear search'),
        ('searchResults', 'Matches'),
        ('searchEmpty', 'No matching files'),
        ('recentSource', 'Recent'),
        ('workspaces', 'Linked folders'),
        ('scanning', 'Scanning'),
        ('fileCount', 'files'),
        ('readOnly', 'read-only'),
        ('unbind', 'Unlink folder'),
        ('working', 'Working'),
        ('workspaceEmpty', 'No Markdown files in this folder'),
        ('permissionLost', 'Access to this folder was revoked; it has been unlinked'),
        ('scanFailed', 'Could not read this folder'),
        ('noPicker', 'No file picker available on this device'),
        ('bindFailed', 'Could not link that folder'),
        ('openFailed', 'Could not open this file'),
    ],
    'zh-CN': [
        ('searchPlaceholder', '搜索已绑定目录和最近打开的文件'),
        ('searchClear', '清除搜索'),
        ('searchResults', '搜索结果'),
        ('searchEmpty', '没有匹配的文件'),
        ('recentSource', '最近'),
        ('workspaces', '已绑定目录'),
        ('scanning', '扫描中'),
        ('fileCount', '个文件'),
        ('readOnly', '只读'),
        ('unbind', '解绑目录'),
        ('working', '处理中'),
        ('workspaceEmpty', '这个目录里没有 Markdown 文件'),
        ('permissionLost', '该目录的访问权限已失效，已自动解绑'),
        ('scanFailed', '无法读取该目录'),
        ('noPicker', '当前设备没有可用的文件选择器'),
        ('bindFailed', '绑定该目录失败'),
        ('openFailed', '无法打开该文件'),
    ],
    'zh-TW': [
        ('searchPlaceholder', '搜尋已綁定目錄與最近開啟的檔案'),
        ('searchClear', '清除搜尋'),
        ('searchResults', '搜尋結果'),
        ('searchEmpty', '沒有符合的檔案'),
        ('recentSource', '最近'),
        ('workspaces', '已綁定目錄'),
        ('scanning', '掃描中'),
        ('fileCount', '個檔案'),
        ('readOnly', '唯讀'),
        ('unbind', '解除綁定'),
        ('working', '處理中'),
        ('workspaceEmpty', '這個目錄裡沒有 Markdown 檔案'),
        ('permissionLost', '該目錄的存取權限已失效，已自動解除綁定'),
        ('scanFailed', '無法讀取該目錄'),
        ('noPicker', '目前裝置沒有可用的檔案選擇器'),
        ('bindFailed', '綁定該目錄失敗'),
        ('openFailed', '無法開啟該檔案'),
    ],
    'de': [
        ('searchPlaceholder', 'Verknüpfte Ordner und zuletzt geöffnete Dateien durchsuchen'),
        ('searchClear', 'Suche löschen'),
        ('searchResults', 'Treffer'),
        ('searchEmpty', 'Keine passenden Dateien'),
        ('recentSource', 'Zuletzt'),
        ('workspaces', 'Verknüpfte Ordner'),
        ('scanning', 'Wird gescannt'),
        ('fileCount', 'Dateien'),
        ('readOnly', 'schreibgeschützt'),
        ('unbind', 'Ordner trennen'),
        ('working', 'Läuft'),
        ('workspaceEmpty', 'Keine Markdown-Dateien in diesem Ordner'),
        ('permissionLost', 'Der Zugriff auf diesen Ordner wurde entzogen; die Verknüpfung wurde entfernt'),
        ('scanFailed', 'Dieser Ordner konnte nicht gelesen werden'),
        ('noPicker', 'Auf diesem Gerät ist keine Dateiauswahl verfügbar'),
        ('bindFailed', 'Der Ordner konnte nicht verknüpft werden'),
        ('openFailed', 'Diese Datei konnte nicht geöffnet werden'),
    ],
    'es': [
        ('searchPlaceholder', 'Buscar en carpetas vinculadas y archivos recientes'),
        ('searchClear', 'Borrar búsqueda'),
        ('searchResults', 'Coincidencias'),
        ('searchEmpty', 'No hay archivos coincidentes'),
        ('recentSource', 'Recientes'),
        ('workspaces', 'Carpetas vinculadas'),
        ('scanning', 'Escanеando'),
        ('fileCount', 'archivos'),
        ('readOnly', 'solo lectura'),
        ('unbind', 'Desvincular carpeta'),
        ('working', 'Procesando'),
        ('workspaceEmpty', 'No hay archivos Markdown en esta carpeta'),
        ('permissionLost', 'Se revocó el acceso a esta carpeta; se ha desvinculado'),
        ('scanFailed', 'No se pudo leer esta carpeta'),
        ('noPicker', 'No hay selector de archivos disponible en este dispositivo'),
        ('bindFailed', 'No se pudo vincular esa carpeta'),
        ('openFailed', 'No se pudo abrir este archivo'),
    ],
    'fr': [
        ('searchPlaceholder', 'Rechercher dans les dossiers liés et les fichiers récents'),
        ('searchClear', 'Effacer la recherche'),
        ('searchResults', 'Résultats'),
        ('searchEmpty', 'Aucun fichier correspondant'),
        ('recentSource', 'Récents'),
        ('workspaces', 'Dossiers liés'),
        ('scanning', 'Analyse en cours'),
        ('fileCount', 'fichiers'),
        ('readOnly', 'lecture seule'),
        ('unbind', 'Délier le dossier'),
        ('working', 'En cours'),
        ('workspaceEmpty', 'Aucun fichier Markdown dans ce dossier'),
        ('permissionLost', "L'accès à ce dossier a été révoqué ; il a été délié"),
        ('scanFailed', 'Impossible de lire ce dossier'),
        ('noPicker', 'Aucun sélecteur de fichiers disponible sur cet appareil'),
        ('bindFailed', 'Impossible de lier ce dossier'),
        ('openFailed', "Impossible d'ouvrir ce fichier"),
    ],
    'ja': [
        ('searchPlaceholder', '連携フォルダーと最近のファイルを検索'),
        ('searchClear', '検索をクリア'),
        ('searchResults', '検索結果'),
        ('searchEmpty', '一致するファイルがありません'),
        ('recentSource', '最近'),
        ('workspaces', '連携フォルダー'),
        ('scanning', 'スキャン中'),
        ('fileCount', 'ファイル'),
        ('readOnly', '読み取り専用'),
        ('unbind', 'フォルダーを解除'),
        ('working', '処理中'),
        ('workspaceEmpty', 'このフォルダーに Markdown ファイルはありません'),
        ('permissionLost', 'このフォルダーへのアクセスが失効したため、連携を解除しました'),
        ('scanFailed', 'このフォルダーを読み取れませんでした'),
        ('noPicker', 'この端末で利用できるファイル選択ツールがありません'),
        ('bindFailed', 'このフォルダーを連携できませんでした'),
        ('openFailed', 'このファイルを開けませんでした'),
    ],
    'ko': [
        ('searchPlaceholder', '연결된 폴더와 최근 파일 검색'),
        ('searchClear', '검색 지우기'),
        ('searchResults', '검색 결과'),
        ('searchEmpty', '일치하는 파일이 없습니다'),
        ('recentSource', '최근'),
        ('workspaces', '연결된 폴더'),
        ('scanning', '검사 중'),
        ('fileCount', '개 파일'),
        ('readOnly', '읽기 전용'),
        ('unbind', '폴더 연결 해제'),
        ('working', '처리 중'),
        ('workspaceEmpty', '이 폴더에 Markdown 파일이 없습니다'),
        ('permissionLost', '이 폴더의 접근 권한이 해제되어 연결이 끊어졌습니다'),
        ('scanFailed', '이 폴더를 읽을 수 없습니다'),
        ('noPicker', '이 기기에는 사용할 수 있는 파일 선택기가 없습니다'),
        ('bindFailed', '해당 폴더를 연결하지 못했습니다'),
        ('openFailed', '이 파일을 열 수 없습니다'),
    ],
    'pt': [
        ('searchPlaceholder', 'Pesquisar pastas vinculadas e arquivos recentes'),
        ('searchClear', 'Limpar pesquisa'),
        ('searchResults', 'Resultados'),
        ('searchEmpty', 'Nenhum arquivo correspondente'),
        ('recentSource', 'Recentes'),
        ('workspaces', 'Pastas vinculadas'),
        ('scanning', 'Analisando'),
        ('fileCount', 'arquivos'),
        ('readOnly', 'somente leitura'),
        ('unbind', 'Desvincular pasta'),
        ('working', 'Processando'),
        ('workspaceEmpty', 'Nao ha arquivos Markdown nesta pasta'),
        ('permissionLost', 'O acesso a esta pasta foi revogado; ela foi desvinculada'),
        ('scanFailed', 'Nao foi possivel ler esta pasta'),
        ('noPicker', 'Nao ha seletor de arquivos disponivel neste dispositivo'),
        ('bindFailed', 'Nao foi possivel vincular essa pasta'),
        ('openFailed', 'Nao foi possivel abrir este arquivo'),
    ],
    'tr': [
        ('searchPlaceholder', 'Bagli klasorlerde ve son dosyalarda ara'),
        ('searchClear', 'Aramayi temizle'),
        ('searchResults', 'Sonuclar'),
        ('searchEmpty', 'Eşlesen dosya yok'),
        ('recentSource', 'Son kullanilan'),
        ('workspaces', 'Bagli klasorler'),
        ('scanning', 'Taranıyor'),
        ('fileCount', 'dosya'),
        ('readOnly', 'salt okunur'),
        ('unbind', 'Klasor baglantisini kes'),
        ('working', 'Isleniyor'),
        ('workspaceEmpty', 'Bu klasorde Markdown dosyasi yok'),
        ('permissionLost', 'Bu klasore erisim kaldirildi; baglanti kesildi'),
        ('scanFailed', 'Bu klasor okunamadi'),
        ('noPicker', 'Bu cihazda kullanilabilir dosya secici yok'),
        ('bindFailed', 'Bu klasor baglanamadi'),
        ('openFailed', 'Bu dosya acilamadi'),
    ],
}

ANCHOR = "'files.emptyBody':"


def escape_ts(value):
    return value.replace('\\', '\\\\').replace("'", "\\'")


def inject(path, pairs):
    with io.open(path, encoding='utf-8') as fh:
        lines = fh.read().split('\n')
    existing = set()
    for line in lines:
        m = re.match(r"\s*'(files\.[A-Za-z]+)':", line)
        if m:
            existing.add(m.group(1))
    anchor_idx = None
    for i, line in enumerate(lines):
        if line.strip().startswith(ANCHOR):
            anchor_idx = i
            break
    if anchor_idx is None:
        print('  !! anchor not found: %s' % path)
        return 0
    new_lines = []
    for key, text in pairs:
        full = 'files.%s' % key
        if full in existing:
            continue
        new_lines.append("  '%s': '%s'," % (full, escape_ts(text)))
    if not new_lines:
        print('  -- nothing to do: %s' % os.path.basename(path))
        return 0
    lines[anchor_idx + 1:anchor_idx + 1] = new_lines
    with io.open(path, 'w', encoding='utf-8') as fh:
        fh.write('\n'.join(lines))
    print('  ++ %s: +%d keys' % (os.path.basename(path), len(new_lines)))
    return len(new_lines)


total = 0
for loc, pairs in TRANSLATIONS.items():
    path = os.path.join(BASE, '%s.ts' % loc)
    if not os.path.exists(path):
        print('  !! missing %s' % path)
        continue
    total += inject(path, pairs)
print('TOTAL injected: %d' % total)
