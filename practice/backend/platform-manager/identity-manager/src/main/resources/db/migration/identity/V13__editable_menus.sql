CREATE TABLE identity_menu (
 code VARCHAR(100) PRIMARY KEY,
 name VARCHAR(100) NOT NULL,
 group_name VARCHAR(100) NOT NULL,
 path VARCHAR(250) NOT NULL UNIQUE,
 description VARCHAR(500) NOT NULL,
 sort_order INTEGER NOT NULL DEFAULT 0,
 revision BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE identity_menu_permission (
 menu_code VARCHAR(100) NOT NULL REFERENCES identity_menu(code),
 permission VARCHAR(100) NOT NULL,
 PRIMARY KEY(menu_code,permission)
);
INSERT INTO identity_menu(code,name,group_name,path,description,sort_order) VALUES
 ('page:tenants','租户管理','平台管理','/platform/tenants','查看、创建和维护租户，管理租户成员',10),
 ('page:users','用户管理','权限管理','/access/users','查看、新建和启停用户，为用户分配角色',20),
 ('page:roles','角色管理','权限管理','/access/roles','创建和维护角色，配置角色可访问的页面',30),
 ('page:menus','菜单管理','权限管理','/access/menus','维护菜单定义和接口关联，查看页面授权角色',40),
 ('page:categories','产品目录','产品与设备','/catalog/:scope/categories','查看和维护产品分类目录',50),
 ('page:products','产品管理','产品与设备','/catalog/:scope/products','查看和维护产品、物模型，发布版本及启停产品',60),
 ('page:devices','设备管理','产品与设备','/catalog/:scope/devices','查看、登记和维护设备，轮换凭证；登记时可选择已有产品',70);
INSERT INTO identity_menu_permission(menu_code,permission) VALUES
 ('page:tenants','tenant:read'),('page:tenants','tenant:write'),('page:tenants','tenant:member:read'),('page:tenants','tenant:member:write'),('page:tenants','iam:user:write'),
 ('page:users','iam:user:read'),('page:users','iam:user:write'),('page:users','iam:role:read'),('page:users','iam:grant'),('page:users','tenant:member:write'),('page:users','tenant:read'),
 ('page:roles','iam:role:read'),('page:roles','iam:role:write'),
 ('page:menus','iam:role:read'),('page:menus','iam:role:write'),
 ('page:categories','product:read'),('page:categories','product:write'),
 ('page:products','product:read'),('page:products','product:write'),('page:products','product:publish'),
 ('page:devices','device:read'),('page:devices','device:write'),('page:devices','device:credential'),('page:devices','product:read');
