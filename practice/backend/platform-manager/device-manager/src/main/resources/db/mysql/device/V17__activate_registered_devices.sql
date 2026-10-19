-- Registration now grants access immediately; no commissioning/approval workflow exists.
-- Preserve identities, credentials, models, disabled devices and retired devices.
ALTER TABLE device_registration ALTER COLUMN lifecycle SET DEFAULT 'ACTIVE';
UPDATE device_registration SET lifecycle='ACTIVE', revision=revision+1 WHERE lifecycle='REGISTERED';
ALTER TABLE device_registration ADD CONSTRAINT device_registration_operational_lifecycle
 CHECK (lifecycle IN ('ACTIVE','DISABLED','RETIRED'));
