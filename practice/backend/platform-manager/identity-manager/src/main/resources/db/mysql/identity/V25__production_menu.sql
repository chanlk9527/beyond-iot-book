INSERT INTO identity_menu(code,name,group_name,path,description,sort_order) VALUES
 ('page:production','生产与交付','产品与设备','/catalog/:scope/production','生产批次、凭证烧录、出厂测试、放行及交付追溯',80);
INSERT INTO identity_menu_permission(menu_code,permission) VALUES
 ('page:production','production:read'),('page:production','production:write'),('page:production','production:credential'),
 ('page:production','product:read'),('page:production','device:read'),('page:production','device:write'),('page:production','device:credential');
