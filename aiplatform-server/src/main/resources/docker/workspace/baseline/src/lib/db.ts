import { Pool } from "pg";
import { createClient } from "redis";

// 工作区 .env 注入的连接串（DATABASE_URL / REDIS_URL，平台唯一注入通道）；
// Next 从项目根 .env 加载进 process.env。连接惰性建立——骨架页阶段不连库，
// 切片实现时按需 query / connect。
export const db = new Pool({ connectionString: process.env.DATABASE_URL });

export const redis = createClient({ url: process.env.REDIS_URL });
