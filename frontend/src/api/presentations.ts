export type WorkflowPresentation =
  | ActivityPresentation
  | CitationPresentation
  | SourcePresentation
  | ArtifactPresentation
  | ConfirmationPresentation
  | UnavailablePresentation
  | ErrorPresentation

interface PresentationBase {
  id: string
  kind: string
}

export interface ActivityPresentation extends PresentationBase {
  kind: 'activity'
  label: string
  status: 'running' | 'completed' | 'failed'
  operationLabel: string | null
}

export interface CitationPresentation extends PresentationBase {
  kind: 'citation'
  sourceName: string
  url: string
  excerpt: string | null
}

export interface SourcePresentation extends PresentationBase {
  kind: 'source'
  sourceName: string
  sourceType: 'document' | 'sheet' | 'slide' | 'base' | 'minutes' | 'file'
  summary: string
  url: string
}

export interface ArtifactPresentation extends PresentationBase {
  kind: 'artifact'
  title: string
  artifactType: 'research' | 'execution_plan'
  status: 'loading' | 'partial' | 'completed' | 'failed' | 'unavailable'
  summary: string
  sections: ArtifactSection[]
}

export interface ArtifactSection {
  heading: string
  body: string
}

export interface ConfirmationPresentation extends PresentationBase {
  kind: 'confirmation'
  title: string
  description: string
  operationLabel: string
  confirmLabel: string
  status: 'review_required'
}

export interface UnavailablePresentation extends PresentationBase {
  kind: 'unavailable'
  title: string
  description: string
}

export interface ErrorPresentation extends PresentationBase {
  kind: 'error'
  title: string
  message: string
}

export function isWorkflowPresentation(value: unknown): value is WorkflowPresentation {
  if (!isRecord(value) || !hasText(value.id) || !hasText(value.kind)) return false
  switch (value.kind) {
    case 'activity':
      return hasText(value.label)
        && ['running', 'completed', 'failed'].includes(value.status as string)
        && (value.operationLabel === null || hasText(value.operationLabel))
    case 'citation':
      return hasText(value.sourceName)
        && isSafeLarkUrl(value.url)
        && (value.excerpt === null || hasText(value.excerpt))
    case 'source':
      return hasText(value.sourceName)
        && ['document', 'sheet', 'slide', 'base', 'minutes', 'file'].includes(value.sourceType as string)
        && hasText(value.summary)
        && isSafeLarkUrl(value.url)
    case 'artifact':
      return hasText(value.title)
        && ['research', 'execution_plan'].includes(value.artifactType as string)
        && ['loading', 'partial', 'completed', 'failed', 'unavailable'].includes(value.status as string)
        && hasText(value.summary)
        && Array.isArray(value.sections)
        && value.sections.every(isArtifactSection)
    case 'confirmation':
      return hasText(value.title)
        && hasText(value.description)
        && hasText(value.operationLabel)
        && hasText(value.confirmLabel)
        && value.status === 'review_required'
    case 'unavailable':
      return hasText(value.title) && hasText(value.description)
    case 'error':
      return hasText(value.title) && hasText(value.message)
    default:
      return false
  }
}

export function isSafeLarkUrl(value: unknown): value is string {
  if (typeof value !== 'string') return false
  try {
    const url = new URL(value)
    const host = url.hostname.toLocaleLowerCase()
    return url.protocol === 'https:'
      && (host === 'larksuite.com'
        || host.endsWith('.larksuite.com')
        || host === 'feishu.cn'
        || host.endsWith('.feishu.cn'))
  } catch {
    return false
  }
}

function isArtifactSection(value: unknown): value is ArtifactSection {
  return isRecord(value) && hasText(value.heading) && hasText(value.body)
}

function hasText(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}
