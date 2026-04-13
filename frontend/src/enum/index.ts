export enum SetupStoreId {
  App = 'app-store',
  Theme = 'theme-store',
  Auth = 'auth-store',
  Route = 'route-store',
  Tab = 'tab-store',
  KnowledgeBase = 'knowledge-base-store',
  Chat = 'chat-store'
}
export enum UploadStatus {
  Uploading = 0,
  Completed = 1,
  Pending = 2,
  Paused = 3,
  Break = 4
}

/**
 * 文件解析状态 (对应后端 FileUpload.parse_status)
 */
export enum ParseStatus {
  PENDING = 'PENDING',      // 待解析
  PROCESSING = 'PROCESSING', // 解析中
  COMPLETED = 'COMPLETED',  // 解析完成
  FAILED = 'FAILED'         // 解析失败
}
