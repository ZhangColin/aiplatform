-- ============================================================================
-- 演示种子数据 · 通用示例集引擎（#340 演示种子数据预置）
-- ----------------------------------------------------------------------------
-- 性质：平台确定性预置进沙箱真库（非模型义务化）。幂等＝只补空表——模型已产
--      数据或用户已操作的表一概不碰。best-effort＝单表异常只 NOTICE 跳过，绝不
--      炸整体、绝不阻断起跑/拉起/收口。
-- 承载：本文件即「配置或资源文件承载」的正本（随镜像 /opt/seed 就位）——调示例
--      内容只改下方配置区值池；引擎区与值池分离，勿混改。
-- 约定：值池长度 ≥ 预置行数 k_rows（唯一列不循环到撞值）；值池文案避免「演示/
--      示例」字样（界面内部零演示标注——演示限制/标注位归 Demo 页外壳）。
-- 速度触发器留档（票面）：实测落库对 Demo 速度影响非常大时再议降形态方案。
-- 调用方：/opt/seed/apply.sh（容器自愈 init-workspace.sh、应用拉起命令、平台
--      run 收口 exec 三处）。
-- ============================================================================

DO $seed$
DECLARE
    -- ---------- 配置区：通用示例集值池（内容可维护正本） ----------
    k_rows int := 10;                     -- 每空表预置行数
    excl   text := '(^|_)migrations?$';   -- 表名排除（迁移簿等非业务表）
    pool_person  text[] := ARRAY['陈明','李慧','王磊','张静','刘畅','赵越','孙倩','周航','吴桐','郑楠','冯远','韩雪','蒋川','沈悦','顾峰','林岚'];
    pool_company text[] := ARRAY['星辰科技','蓝海贸易','绿洲文创','恒信实业','云帆网络','晨光教育','瑞丰物流','拓达咨询','远见传媒','博雅文化','天工制造','四海餐饮','明轩设计','极光电子','沃野农业','清泉环保'];
    pool_title   text[] := ARRAY['标准方案','基础方案','进阶方案','旗舰方案','日常事务','重点事项','常规记录','专项跟进','季度安排','年度规划','特别任务','临时条目'];
    pool_note    text[] := ARRAY['信息完整，正常在档','已核对的常规记录','待进一步跟进确认','资料齐全，可随时查阅','初步录入，内容无误','按流程处理完毕','例行更新，无异常','已归档备查','首次登记，信息完整','近期有过更新','状态与实际一致','已完成核验'];
    pool_status  text[] := ARRAY['进行中','待处理','已确认','已完成','已交付','待审核','已发布','已结束'];
    pool_addr    text[] := ARRAY['和平路 12 号','科技大道 88 号','中山路 5 号','创新大道 66 号','环湖路 99 号','建设路 33 号','江南大道 108 号','迎宾大道 21 号','文华路 45 号','春晓街 77 号','长风路 8 号','望江路 56 号'];
    pool_amount  numeric[] := ARRAY[199,299,399,599,899,1299,1999,2999,3999,4999,5999,6999];

    -- ---------- 引擎区（按列形合成通用示例行，勿动） ----------
    tab record;
    colr record;
    has_rows boolean;
    cols text;
    vals text;
    row_vals text;
    frag text;
    v text;
    maxlen int;
    fk_ref oid;
    fk_col text;
    enum_vals text[];
BEGIN
    -- 依外键依赖深度排序（父先子后；深度上界 16 防环），只扫 public 普通表/
    -- 分区父表，迁移簿类排除
    FOR tab IN
        WITH RECURSIVE edges AS (
            SELECT con.conrelid AS dep, con.confrelid AS ref
              FROM pg_constraint con
             WHERE con.contype = 'f'
        ),
        dep AS (
            SELECT cl.oid AS relid, 0 AS d
              FROM pg_class cl JOIN pg_namespace ns ON ns.oid = cl.relnamespace
             WHERE ns.nspname = 'public' AND cl.relkind IN ('r', 'p')
            UNION ALL
            -- 边向＝从被引用（父）走向依赖方（子）：深度＝距无父根的最远跳数，
            -- 父恒不大于子（父先种）
            SELECT e.dep, dep.d + 1
              FROM dep JOIN edges e ON e.ref = dep.relid
             WHERE dep.d < 16
        )
        SELECT cl.oid AS tbl_oid, cl.relname AS tbl
          FROM pg_class cl JOIN pg_namespace ns ON ns.oid = cl.relnamespace
          LEFT JOIN dep ON dep.relid = cl.oid
         WHERE ns.nspname = 'public' AND cl.relkind IN ('r', 'p')
           AND cl.relname !~ excl
         GROUP BY cl.oid, cl.relname
         ORDER BY max(coalesce(dep.d, 0)), cl.relname
    LOOP
        BEGIN
            -- 复合外键表整体跳过（合成器只支持单列外键）
            IF EXISTS (SELECT 1 FROM pg_constraint con
                        WHERE con.contype = 'f' AND con.conrelid = tab.tbl_oid
                          AND array_length(con.conkey, 1) > 1) THEN
                RAISE NOTICE '[seed] % 跳过：复合外键', tab.tbl;
                CONTINUE;
            END IF;
            -- 幂等判据：已有数据（模型自产/用户操作/先前预置）即不碰
            EXECUTE format('SELECT EXISTS (SELECT 1 FROM %I)', tab.tbl) INTO has_rows;
            IF has_rows THEN
                CONTINUE;
            END IF;

            cols := '';
            vals := '';
            FOR i IN 1..k_rows LOOP
                row_vals := '';
                FOR colr IN
                    SELECT a.attname AS colname, a.attnum AS attnum,
                           a.attnotnull AS notnull, a.atttypid AS typid, a.atttypmod AS typmod,
                           (SELECT ty.typname FROM pg_type ty WHERE ty.oid = a.atttypid) AS typname,
                           (SELECT ty.typtype FROM pg_type ty WHERE ty.oid = a.atttypid) AS typtype,
                           pg_get_expr(ad.adbin, ad.adrelid) AS defexpr
                      FROM pg_attribute a
                      LEFT JOIN pg_attrdef ad ON ad.adrelid = a.attrelid AND ad.adnum = a.attnum
                     WHERE a.attrelid = tab.tbl_oid AND a.attnum > 0
                       AND NOT a.attisdropped AND a.attgenerated = ''
                     ORDER BY a.attnum
                LOOP
                    frag := NULL;
                    IF colr.defexpr IS NOT NULL THEN
                        NULL;  -- 有默认值的列不代填（默认自填：serial/identity/now() 等）
                    ELSE
                        -- 单列外键：引用父表真实键（父按深度先种；父空则子查询得
                        -- NULL——可空列自然落 NULL，非空列违约束由本表异常兜底跳过）
                        SELECT con.confrelid, rf.attname INTO fk_ref, fk_col
                          FROM pg_constraint con
                          LEFT JOIN pg_attribute rf
                                 ON rf.attrelid = con.confrelid AND rf.attnum = con.confkey[1]
                         WHERE con.contype = 'f' AND con.conrelid = tab.tbl_oid
                           AND con.conkey[1] = colr.attnum
                           AND array_length(con.conkey, 1) = 1
                         LIMIT 1;
                        IF fk_ref IS NOT NULL AND fk_col IS NOT NULL THEN
                            frag := format('(SELECT p.%I FROM %s p ORDER BY p.%I LIMIT 1 OFFSET %s)',
                                           fk_col, fk_ref::regclass::text, fk_col, i - 1);
                        ELSIF colr.typtype = 'e' THEN
                            SELECT array_agg(e.enumlabel ORDER BY e.enumsortorder)
                              INTO enum_vals
                              FROM pg_enum e WHERE e.enumtypid = colr.typid;
                            IF enum_vals IS NOT NULL AND array_length(enum_vals, 1) > 0 THEN
                                frag := quote_literal(
                                    enum_vals[((i - 1) % array_length(enum_vals, 1)) + 1]);
                            END IF;
                        ELSIF colr.typname = 'uuid' THEN
                            -- 无默认 uuid（含主键）：表.列.行 定散列——确定性且不重
                            frag := format('md5(%L)::uuid', tab.tbl || '.' || colr.colname || '.' || i);
                        ELSIF colr.typname IN ('int2', 'int4', 'int8') THEN
                            frag := i::text;
                        ELSIF colr.typname IN ('numeric', 'float4', 'float8') THEN
                            frag := pool_amount[((i - 1) % array_length(pool_amount, 1)) + 1]::text;
                        ELSIF colr.typname = 'bool' THEN
                            frag := (i % 2 = 0)::text;
                        ELSIF colr.typname = 'date' THEN
                            frag := quote_literal((current_date - (i * 3))::text);
                        ELSIF colr.typname IN ('timestamp', 'timestamptz') THEN
                            frag := quote_literal((current_date - (i * 3))::text || ' '
                                    || lpad((8 + (i % 10))::text, 2, '0') || ':30:00');
                        ELSIF colr.typname IN ('json', 'jsonb') THEN
                            frag := quote_literal(format('{"row": %s}', i));
                        ELSIF colr.typname IN ('text', 'varchar', 'bpchar') THEN
                            maxlen := CASE WHEN colr.typname IN ('varchar', 'bpchar')
                                              AND colr.typmod > 4
                                           THEN colr.typmod - 4 ELSE 0 END;
                            v := CASE
                                WHEN lower(colr.colname) ~ '(name|title|subject|label|名称|标题|主题|事项|昵称)' THEN
                                    CASE
                                        WHEN lower(colr.colname) ~ '(customer|client|guest|contact|member|user|student|teacher|applicant|owner|assignee|handler|客户|顾客|会员|联系人|用户|学员|申请人|负责|经办|员工|人员)' THEN
                                            pool_person[((i - 1) % array_length(pool_person, 1)) + 1]
                                        WHEN lower(colr.colname) ~ '(company|corp|org|supplier|vendor|merchant|tenant|school|企业|公司|单位|机构|部门|供应商|商家)' THEN
                                            pool_company[((i - 1) % array_length(pool_company, 1)) + 1]
                                        ELSE pool_title[((i - 1) % array_length(pool_title, 1)) + 1]
                                    END
                                WHEN lower(colr.colname) ~ '(phone|mobile|tel|手机|电话|联系)' THEN
                                    '138' || lpad((i * 1234567)::text, 8, '0')
                                WHEN lower(colr.colname) ~ '(mail|邮箱)' THEN
                                    'contact' || i || '@example.com'
                                WHEN lower(colr.colname) ~ '(addr|address|location|地址|住址)' THEN
                                    pool_addr[((i - 1) % array_length(pool_addr, 1)) + 1]
                                WHEN lower(colr.colname) ~ '(_no$|no$|code|sn|编号|单号|序号)' THEN
                                    'NO-' || to_char(current_date, 'YYMMDD') || '-' || lpad(i::text, 3, '0')
                                WHEN lower(colr.colname) ~ '(desc|remark|note|comment|intro|detail|content|summary|说明|备注|描述|介绍|详情|内容|摘要)' THEN
                                    pool_note[((i - 1) % array_length(pool_note, 1)) + 1]
                                WHEN lower(colr.colname) ~ '(status|state|stage|phase|category|kind|type|tag|状态|阶段|类别|类型|标签)' THEN
                                    pool_status[((i - 1) % array_length(pool_status, 1)) + 1]
                                WHEN lower(colr.colname) ~ '(url|link|avatar|image|img|icon|photo|cover|链接|头像|图片|封面)' THEN
                                    'https://example.com/asset-' || i || '.png'
                                -- 裸人位/机构位列（无 name 后缀，看板 assignee、owner 等常见形）
                                WHEN lower(colr.colname) ~ '(customer|client|guest|contact|member|user|student|teacher|applicant|owner|assignee|handler|负责人|经办人|联系人|客户|顾客|会员|用户|学员|申请人|员工|人员)' THEN
                                    pool_person[((i - 1) % array_length(pool_person, 1)) + 1]
                                WHEN lower(colr.colname) ~ '(company|corp|org|supplier|vendor|merchant|tenant|school|企业|公司|单位|机构|部门|供应商|商家)' THEN
                                    pool_company[((i - 1) % array_length(pool_company, 1)) + 1]
                                ELSE NULL
                            END;
                            -- 必填文本无语义匹配 → 说明类池兜底；可空则整列省略
                            IF v IS NULL AND colr.notnull THEN
                                v := pool_note[((i - 1) % array_length(pool_note, 1)) + 1];
                            END IF;
                            IF v IS NOT NULL THEN
                                IF maxlen > 0 AND length(v) > maxlen THEN
                                    v := left(v, maxlen);
                                END IF;
                                frag := quote_literal(v);
                            END IF;
                        ELSIF colr.notnull THEN
                            -- 未识别类型的必填列给空复合字面量（数组可收、其余由
                            -- 表级异常兜底跳过）
                            frag := quote_literal('{}');
                        END IF;
                    END IF;
                    IF frag IS NOT NULL THEN
                        row_vals := row_vals || ', ' || frag;
                        IF i = 1 THEN
                            cols := cols || ', ' || quote_ident(colr.colname);
                        END IF;
                    END IF;
                END LOOP;
                vals := vals || ', (' || substr(row_vals, 3) || ')';
            END LOOP;

            IF cols = '' THEN
                -- 全列皆有默认：默认值即种子
                FOR i IN 1..k_rows LOOP
                    EXECUTE format('INSERT INTO %I DEFAULT VALUES', tab.tbl);
                END LOOP;
            ELSE
                EXECUTE format('INSERT INTO %I (%s) VALUES %s',
                               tab.tbl, substr(cols, 3), substr(vals, 3));
            END IF;
            RAISE NOTICE '[seed] % 预置 % 行', tab.tbl, k_rows;
        EXCEPTION WHEN OTHERS THEN
            RAISE NOTICE '[seed] % 跳过（%）', tab.tbl, SQLERRM;
        END;
    END LOOP;
END
$seed$;
