-- Demonstration data only. Run separately: mysql -u root -p < backend/sample_data.sql
USE spendwise;
INSERT INTO income (amount, source, description, income_date) VALUES
 (15000,'Pocket Money','Monthly allowance','2026-10-01'),
 (3000,'Freelance','Logo design','2026-10-12');
INSERT INTO expenses (amount, category, description, expense_date) VALUES
 (250,'Food','Canteen lunch','2026-10-03'),
 (1200,'Education','Books','2026-10-05'),
 (450,'Entertainment','Movie night','2026-10-14'),
 (800,'Shopping','T-shirts','2026-10-20');
