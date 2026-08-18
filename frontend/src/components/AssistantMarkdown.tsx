import Markdown, { type Components } from 'react-markdown'

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
]

const components: Components = {
  a: ({ children, href }) => href ? (
    <a href={href} target="_blank" rel="noreferrer noopener">{children}</a>
  ) : <span>{children}</span>,
}

export function AssistantMarkdown({ children }: { children: string }) {
  return (
    <Markdown
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

function safeHttpUrl(url: string) {
  try {
    const parsed = new URL(url)
    return parsed.protocol === 'https:' || parsed.protocol === 'http:' ? url : ''
  } catch {
    return ''
  }
}
