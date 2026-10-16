-- Unify tenant naming while preserving all rows and foreign key relationships.
ALTER TABLE tenant_workspace RENAME TO tenant;
