import type { LanguageFn } from 'highlight.js'
import hljs from 'highlight.js/lib/core'
import bash from 'highlight.js/lib/languages/bash'
import cpp from 'highlight.js/lib/languages/cpp'
import csharp from 'highlight.js/lib/languages/csharp'
import css from 'highlight.js/lib/languages/css'
import diff from 'highlight.js/lib/languages/diff'
import dockerfile from 'highlight.js/lib/languages/dockerfile'
import go from 'highlight.js/lib/languages/go'
import http from 'highlight.js/lib/languages/http'
import ini from 'highlight.js/lib/languages/ini'
import java from 'highlight.js/lib/languages/java'
import javascript from 'highlight.js/lib/languages/javascript'
import json from 'highlight.js/lib/languages/json'
import kotlin from 'highlight.js/lib/languages/kotlin'
import markdown from 'highlight.js/lib/languages/markdown'
import plaintext from 'highlight.js/lib/languages/plaintext'
import powershell from 'highlight.js/lib/languages/powershell'
import python from 'highlight.js/lib/languages/python'
import rust from 'highlight.js/lib/languages/rust'
import scss from 'highlight.js/lib/languages/scss'
import shell from 'highlight.js/lib/languages/shell'
import sql from 'highlight.js/lib/languages/sql'
import typescript from 'highlight.js/lib/languages/typescript'
import xml from 'highlight.js/lib/languages/xml'
import yaml from 'highlight.js/lib/languages/yaml'

const registeredLanguages: Array<[string, LanguageFn]> = [
  ['bash', bash],
  ['cpp', cpp],
  ['csharp', csharp],
  ['css', css],
  ['diff', diff],
  ['dockerfile', dockerfile],
  ['go', go],
  ['http', http],
  ['ini', ini],
  ['java', java],
  ['javascript', javascript],
  ['json', json],
  ['kotlin', kotlin],
  ['markdown', markdown],
  ['plaintext', plaintext],
  ['powershell', powershell],
  ['python', python],
  ['rust', rust],
  ['scss', scss],
  ['shell', shell],
  ['sql', sql],
  ['typescript', typescript],
  ['xml', xml],
  ['yaml', yaml],
]

for (const [name, language] of registeredLanguages) {
  hljs.registerLanguage(name, language)
}

const languageAliases: Record<string, string> = {
  c: 'cpp',
  cs: 'csharp',
  docker: 'dockerfile',
  env: 'ini',
  htm: 'xml',
  html: 'xml',
  js: 'javascript',
  jsonc: 'json',
  jsx: 'javascript',
  md: 'markdown',
  ps1: 'powershell',
  py: 'python',
  rs: 'rust',
  sh: 'bash',
  shellscript: 'bash',
  text: 'plaintext',
  ts: 'typescript',
  tsx: 'typescript',
  txt: 'plaintext',
  vue: 'xml',
  xhtml: 'xml',
  xml: 'xml',
  yml: 'yaml',
  zsh: 'bash',
}

export function normalizeHighlightLanguage(language?: string) {
  if (!language) return undefined

  const normalized = language.trim().toLowerCase()
  const resolved = languageAliases[normalized] ?? normalized

  return hljs.getLanguage(resolved) ? resolved : undefined
}

export function highlightCode(code: string, language?: string) {
  const resolvedLanguage = normalizeHighlightLanguage(language)
  if (resolvedLanguage) {
    return hljs.highlight(code, { language: resolvedLanguage }).value
  }

  return hljs.highlightAuto(code).value
}

export function highlightPlainText(code: string) {
  return hljs.highlight(code, { language: 'plaintext' }).value
}

export { hljs }
