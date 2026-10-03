import { afterEach, describe, expect, it } from 'vitest'
import { Capacitor } from '@capacitor/core'
import {
  AndroidImageError,
  collectMarkTextLocalImageFileNames,
  formatImportedImageStorageBytes,
  resolveMarkTextImageSource,
  resolveRelativeDocumentImageSource,
  setAndroidDocumentDirectory,
} from './androidImages'

describe('androidImages', () => {
  it('resolves app-local Markdown image sources', () => {
    expect(
      resolveMarkTextImageSource('marktext-image://local/picked%20image.png', {
        fileUri: 'file:///data/user/0/com.starhashing.perspicuity/files/images',
        webBaseUri: 'http://localhost/images',
      }),
    ).toBe('http://localhost/images/picked%20image.png')
  })

  it('resolves Android URI Markdown image sources through Capacitor', () => {
    expect(
      resolveMarkTextImageSource(
        'marktext-image://android/content%3A%2F%2Fmedia%2Fimage%2F1',
        null,
      ),
    ).toBe('content://media/image/1')
  })

  it('rejects image source filenames that escape the image directory', () => {
    expect(() =>
      resolveMarkTextImageSource('marktext-image://local/..%2Fsecret.png', {
        fileUri: 'file:///data/user/0/com.starhashing.perspicuity/files/images',
      }),
    ).toThrow(AndroidImageError)
  })

  it('rejects non-content Android image source URIs', () => {
    expect(() =>
      resolveMarkTextImageSource(
        'marktext-image://android/file%3A%2F%2Fsdcard%2Fphoto.png',
        null,
      ),
    ).toThrow(AndroidImageError)
  })

  it('ignores app-local image sources until the native directory is available', () => {
    expect(resolveMarkTextImageSource('marktext-image://local/picked.png', null)).toBeNull()
    expect(resolveMarkTextImageSource('marktext-image://local/..%2Fsecret.png', null)).toBeNull()
  })

  it('normalizes invalid percent-encoded image filenames into Android image errors', () => {
    expect(() =>
      resolveMarkTextImageSource('marktext-image://local/%E0%A4%A.png', {
        fileUri: 'file:///data/user/0/com.starhashing.perspicuity/files/images',
      }),
    ).toThrow(AndroidImageError)
  })

  it('collects unique app-local image references from Markdown and HTML', () => {
    expect(collectMarkTextLocalImageFileNames(`
![first](marktext-image://local/1700000000000-a1b2c3d4-first.png)
<img src="marktext-image://local/1700000000001-b2c3d4e5-second.webp">
![duplicate](marktext-image://local/1700000000000-a1b2c3d4-first.png)
![linked](marktext-image://android/content%3A%2F%2Fmedia%2Fimage%2F1)
`)).toEqual([
      '1700000000000-a1b2c3d4-first.png',
      '1700000000001-b2c3d4e5-second.webp',
    ])
  })

  it('formats imported image storage without overstating precision', () => {
    expect(formatImportedImageStorageBytes(0, 'en')).toBe('0 B')
    expect(formatImportedImageStorageBytes(1536, 'en')).toBe('1.5 KB')
    expect(formatImportedImageStorageBytes(5 * 1024 * 1024, 'en')).toBe('5 MB')
  })

  describe('document-relative image sources', () => {
    afterEach(() => {
      // 每个用例后清掉文档目录，避免相互污染。
      setAndroidDocumentDirectory(null)
    })

    it('resolves a bare relative path against the open document directory', () => {
      setAndroidDocumentDirectory('/storage/emulated/0/Docs')
      expect(resolveRelativeDocumentImageSource('image.png')).toBe(
        Capacitor.convertFileSrc('file:///storage/emulated/0/Docs/image.png'),
      )
    })

    it('resolves nested and parent-relative paths by collapsing segments', () => {
      setAndroidDocumentDirectory('/storage/emulated/0/Docs/notes/')
      expect(resolveRelativeDocumentImageSource('screenshots/a.webp')).toBe(
        Capacitor.convertFileSrc('file:///storage/emulated/0/Docs/notes/screenshots/a.webp'),
      )
      expect(resolveRelativeDocumentImageSource('../shared/b.png')).toBe(
        Capacitor.convertFileSrc('file:///storage/emulated/0/Docs/shared/b.png'),
      )
      expect(resolveRelativeDocumentImageSource('./c.png')).toBe(
        Capacitor.convertFileSrc('file:///storage/emulated/0/Docs/notes/c.png'),
      )
    })

    it('decodes percent-encoded relative paths before resolving', () => {
      setAndroidDocumentDirectory('/storage/emulated/0/Docs/')
      expect(resolveRelativeDocumentImageSource('my%20image.png')).toBe(
        Capacitor.convertFileSrc('file:///storage/emulated/0/Docs/my image.png'),
      )
    })

    it('leaves absolute paths, URLs and schemes to other resolvers', () => {
      setAndroidDocumentDirectory('/storage/emulated/0/Docs/')
      expect(resolveRelativeDocumentImageSource('/etc/hosts')).toBeNull()
      expect(resolveRelativeDocumentImageSource('C:\\Users\\shot.png')).toBeNull()
      expect(resolveRelativeDocumentImageSource('https://example.com/a.png')).toBeNull()
      expect(resolveRelativeDocumentImageSource('data:image/png;base64,AAAA')).toBeNull()
      expect(resolveRelativeDocumentImageSource('content://media/image/1')).toBeNull()
    })

    it('returns null when no document directory is known', () => {
      setAndroidDocumentDirectory(null)
      expect(resolveRelativeDocumentImageSource('image.png')).toBeNull()
      expect(resolveMarkTextImageSource('image.png', null)).toBeNull()
    })

    it('clears a stale document directory when the new value is empty', () => {
      setAndroidDocumentDirectory('/storage/emulated/0/Docs/')
      setAndroidDocumentDirectory('   ')
      expect(resolveRelativeDocumentImageSource('image.png')).toBeNull()
    })

    it('falls through to relative resolution from resolveMarkTextImageSource', () => {
      setAndroidDocumentDirectory('/storage/emulated/0/Docs/')
      expect(resolveMarkTextImageSource('docs/a.png', null)).toBe(
        Capacitor.convertFileSrc('file:///storage/emulated/0/Docs/docs/a.png'),
      )
      // 带导入目录但源不是 app-local 图片时，同样回退到相对解析。
      expect(
        resolveMarkTextImageSource('docs/a.png', {
          fileUri: 'file:///data/user/0/com.starhashing.perspicuity/files/images',
        }),
      ).toBe(Capacitor.convertFileSrc('file:///storage/emulated/0/Docs/docs/a.png'))
    })
  })
})
