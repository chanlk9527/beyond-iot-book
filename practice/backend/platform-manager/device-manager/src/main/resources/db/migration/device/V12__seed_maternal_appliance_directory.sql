-- Persist the initial maternal/infant appliance catalogue for existing scopes.
-- Preserve every previously maintained directory, including intentionally empty ones.
INSERT INTO device_product_directory(scope_key,tree_json,revision)
SELECT scopes.scope_key,
'{"revision":1,"items":[{"label":"喂养电器","children":[{"label":"暖奶器","children":[]},{"label":"恒温调奶器","children":[]},{"label":"自动冲奶机","children":[]},{"label":"辅食料理机","children":[]},{"label":"电动吸乳器","children":[]}]},{"label":"清洁消毒","children":[{"label":"奶瓶消毒烘干器","children":[]},{"label":"奶瓶清洗机","children":[]},{"label":"婴儿衣物烘干机","children":[]}]},{"label":"睡眠环境","children":[{"label":"婴儿房加湿器","children":[]},{"label":"婴儿房空气净化器","children":[]},{"label":"白噪音安抚器","children":[]}]},{"label":"护理电器","children":[{"label":"婴儿理发器","children":[]},{"label":"电动吸鼻器","children":[]},{"label":"婴儿恒温浴盆","children":[]}]}]}',1
FROM (SELECT 'platform' AS scope_key UNION SELECT id AS scope_key FROM tenant) scopes
WHERE NOT EXISTS (SELECT 1 FROM device_product_directory existing WHERE existing.scope_key=scopes.scope_key);
