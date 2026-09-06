import { Children, type ReactNode } from 'react'
import Markdown, { type Components } from 'react-markdown'
import { gfmTable } from 'micromark-extension-gfm-table'
import { gfmTableFromMarkdown } from 'mdast-util-gfm-table'

const workspaceReferencePrefix = 'synvo-workspace-reference:'

const allowedElements = [
  'a',
  'blockquote',
  'br',
  'code',
  'em',
  'h1',
  'h2',
  'h3',
  'h4',
  'hr',
  'li',
  'ol',
  'p',
  'pre',
  'strong',
  'ul',
  'table', 'thead', 'tbody', 'tr', 'th', 'td',
]

// Add only table grammar to the existing remark parser; no autolinks or task inputs.
function remarkTables(this: { data(): object }) {
  const data = this.data() as {
    micromarkExtensions?: ReturnType<typeof gfmTable>[]
    fromMarkdownExtensions?: ReturnType<typeof gfmTableFromMarkdown>[]
  }
  const syntax = (data.micromarkExtensions ??= [])
  const tree = (data.fromMarkdownExtensions ??= [])
  syntax.push(gfmTable())
  tree.push(gfmTableFromMarkdown())
}

const components: Components = {
  table: ({ children }) => <div className="assistant-table-scroll" role="region" aria-label="Table — scroll horizontally if needed" tabIndex={0}><table>{children}</table></div>,
  th: ({ children, style }) => <th scope="col" style={style}>{children}</th>,
  td: ({ children, style }) => <td style={style}>{children}</td>,
  a: ({ children, href }) => {
    const workspaceReference = readWorkspaceReference(href)
    if (workspaceReference) {
      return <span className="assistant-workspace-reference">{workspaceReference}</span>
    }
    return href ? (
      <a href={href} target="_blank" rel="noreferrer noopener">{children}</a>
    ) : <span>{children}</span>
  },
  p: ({ children }) => <p>{withSoftLineBreaks(children)}</p>,
}

export function AssistantMarkdown({ children }: { children: string }) {
  const compactEntries = getCompactLineEntries(children)
  if (compactEntries) {
    return (
      <section className="assistant-compact-result" aria-label="Result">
        <p className="assistant-compact-result__label">Result</p>
        <ul className="assistant-compact-list" aria-label="Result entries">
          {compactEntries.map((entry, index) => (
            <li key={`${entry}-${index}`}>{entry}</li>
          ))}
        </ul>
      </section>
    )
  }

  return (
    <Markdown
      remarkPlugins={[remarkTables]}
      allowedElements={allowedElements}
      components={components}
      skipHtml
      unwrapDisallowed
      urlTransform={safeHttpUrl}
    >
      {children}
    </Markdown>
  )
}

function getCompactLineEntries(content: string) {
  const normalized = content.replace(/\r\n?/g, '\n').trim()
  const lines = normalized.split('\n')
  if (lines.length < 3 || lines.length > 64) return null
  if (lines.some((line) => line.trim().length === 0)) return null

  const entries = lines.map((line) => line.trim())
  const containsAuthoredStructure = entries.some((entry) => (
    /^(?:[-+*]\s+|\d+[.)]\s+|#{1,6}\s+|>\s+|```|~~~)/.test(entry)
  ))
  if (containsAuthoredStructure || entries.some((entry) => entry.includes('|'))) return null

  const containsSentenceLikeLine = entries.some((entry) => (
    entry.length > 160 || /[.!?:;]["')\]]?$/.test(entry)
  ))
  if (containsSentenceLikeLine) return null

  return entries
}

function withSoftLineBreaks(children: ReactNode) {
  let breakIndex = 0
  const result: ReactNode[] = []
  for (const child of Children.toArray(children)) {
    if (typeof child !== 'string' || !child.includes('\n')) {
      result.push(child)
      continue
    }
    child.split('\n').forEach((line, index) => {
      if (index > 0) result.push(<br key={`soft-break-${breakIndex++}`} />)
      result.push(line)
    })
  }
  return result
}

function safeHttpUrl(url: string) {
  const workspaceReference = normalizeWorkspaceReference(url)
  if (workspaceReference) {
    return `${workspaceReferencePrefix}${encodeURIComponent(workspaceReference)}`
  }
  try {
    const parsed = new URL(url)
    return parsed.protocol === 'https:' || parsed.protocol === 'http:' ? url : ''
  } catch {
    return ''
  }
}

function normalizeWorkspaceReference(url: string) {
  if (!url.startsWith('./')) return null
  const reference = url.slice(2)
  if (
    !reference
    || reference.includes('\\')
    || reference.includes('?')
    || reference.includes('#')
    || reference.includes('\0')
  ) return null

  const match = /^(.*?)(?::([1-9]\d*))?$/.exec(reference)
  if (!match) return null
  const path = match[1]
  const line = match[2]
  if (
    !path
    || path.includes(':')
    || path.split('/').some((segment) => !segment || segment === '.' || segment === '..')
  ) return null
  return line ? `${path}:${line}` : path
}

function readWorkspaceReference(href: string | undefined) {
  if (!href?.startsWith(workspaceReferencePrefix)) return null
  try {
    return decodeURIComponent(href.slice(workspaceReferencePrefix.length))
  } catch {
    return null
  }
}
