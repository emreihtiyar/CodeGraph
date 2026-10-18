// Backend DTO'larının (me.emreihtiyar.codegraph.query) TypeScript karşılıkları.

export type Layer = 'FRONTEND' | 'BFF' | 'API' | 'ADAPTER' | 'LIBRARY';

export type NodeLabel =
  | 'Service' | 'Screen' | 'Component' | 'ApiCall' | 'Endpoint' | 'Class' | 'Method' | 'FeignClient'
  | 'FeignMethod' | 'JpaRepository' | 'RepositoryMethod' | 'Entity' | 'Table' | 'AnalysisRun';

export type Props = Record<string, unknown>;

export interface NodeSummary {
  id: string;
  label: NodeLabel;
  name: string;
  service: string | null;
  properties: Props;
}

export interface EdgeView {
  type: string;
  source: string;
  target: string;
  properties: Props;
}

export interface Subgraph {
  nodes: NodeSummary[];
  edges: EdgeView[];
  truncated: boolean;
  note: string | null;
}

export interface Service {
  id: string;
  name: string;
  layer: Layer | null;
  repo: string | null;
  contextPath: string | null;
  gatewayPrefix: string | null;
  analyzed: boolean;
  endpointCount: number;
  screenCount: number;
  tableCount: number;
}

export interface ServiceList {
  services: Service[];
  dependencies: { from: string; to: string; callCount: number }[];
}

export interface DependencyLink {
  sourceId: string;
  source: string;
  sourceKind: string;
  sourceService: string | null;
  targetId: string | null;
  target: string | null;
  resolution: string | null;
  reason: string | null;
}

export interface ServiceDependency {
  service: string;
  layer: Layer | null;
  analyzed: boolean;
  callCount: number;
  links: DependencyLink[];
}

export interface ServiceDependencies {
  service: Service;
  outgoing: ServiceDependency[];
  incoming: ServiceDependency[];
}

export interface ScreenView {
  id: string;
  routePath: string;
  name: string;
  service: string;
  componentId: string | null;
}

export interface EndpointView {
  id: string;
  service: string;
  httpMethod: string;
  path: string;
  handlerId: string | null;
  handler: string | null;
}

export interface LayerServices {
  layer: string;
  services: string[];
}

export interface TableAccess {
  id: string;
  name: string;
  access: ('READ' | 'WRITE')[];
}

export interface TraceResult {
  direction: 'FORWARD' | 'BACKWARD';
  start: NodeSummary;
  graph: Subgraph;
  summary: {
    screens: NodeSummary[];
    /** Ekranlardan çıkan istekler (ApiCall). */
    apiCalls: NodeSummary[];
    services: LayerServices[];
    endpoints: NodeSummary[];
    tables: TableAccess[];
    unresolved: NodeSummary[];
  };
}

export interface NodeDetail {
  node: NodeSummary;
  hasCode: boolean;
  outgoing: { edge: EdgeView; node: NodeSummary }[];
  incoming: { edge: EdgeView; node: NodeSummary }[];
  outgoingTotal: number;
  incomingTotal: number;
}

export interface CodeBlock {
  nodeId: string;
  sourceNodeId: string;
  name: string;
  repo: string | null;
  service: string | null;
  file: string | null;
  startLine: number | null;
  endLine: number | null;
  language: string | null;
  code: string;
}

export interface SearchHit {
  node: NodeSummary;
  matchedField: string;
  score: number;
}

export type CallTreeKind = 'METHOD' | 'FEIGN_METHOD' | 'REPOSITORY_METHOD' | 'ENDPOINT' | 'SCREEN' | 'API_CALL';

export interface CallTreeNode {
  nodeId: string;
  kind: CallTreeKind;
  name: string;
  service: string | null;
  visibility: string | null;
  isStatic: boolean;
  file: string | null;
  startLine: number | null;
  endLine: number | null;
  call: Props | null;
  recursive: boolean;
  hasChildren: boolean;
  children: CallTreeNode[] | null;
  details: Props;
}

export interface CallTreeOptions {
  depth: number;
  includeAccessors: boolean;
  onlyExternal: boolean;
  crossService: boolean;
  hidden: string[];
}

export interface AnalysisStatus {
  runId: string;
  state: 'IDLE' | 'RUNNING' | 'SUCCEEDED' | 'FAILED';
  phase: string | null;
  current: string | null;
  done: number;
  total: number;
  startedAt: string | null;
  finishedAt: string | null;
  error: string | null;
}

export interface RepoSummary {
  name: string;
  layer: string;
  service: string | null;
  commitHash: string | null;
  files: number;
  classes: number;
  methods: number;
  calls: number;
  endpoints: number;
  feignMethods: number;
  entities: number;
  apiCalls: number;
  components: number;
  screens: number;
  issues: number;
  durationMs: number;
}

export interface LinkIssue {
  kind: string;
  repo: string;
  source: string;
  targetService: string | null;
  reason: string | null;
  candidates: string[];
}

export interface CallIssue {
  repo: string;
  caller: string;
  expression: string;
  line: number;
  candidates: string[];
}

export interface FileIssue {
  repo: string;
  file: string;
  line: number;
  message: string;
}

export interface AnalysisReport {
  runId: string;
  startedAt: string;
  finishedAt: string;
  durationMs: number;
  nodeCounts: Record<string, number>;
  relationshipCounts: Record<string, number>;
  repos: RepoSummary[];
  unresolvedLinks: LinkIssue[];
  ambiguousLinks: LinkIssue[];
  ambiguousCalls: CallIssue[];
  unresolvedCalls: CallIssue[];
  parseIssues: FileIssue[];
  warnings: string[];
}

export interface AnalysisRunView {
  runId: string;
  state: AnalysisStatus['state'];
  startedAt: string | null;
  finishedAt: string | null;
  durationMs: number | null;
  issueCount: number | null;
  error: string | null;
  status: AnalysisStatus | null;
  report: AnalysisReport | null;
}
