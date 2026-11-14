-- SpendWise schema (requires MySQL 8.0.16+ for CHECK constraints)
CREATE DATABASE IF NOT EXISTS spendwise;
USE spendwise;

-- 1. Personal expenses
CREATE TABLE IF NOT EXISTS expenses (
  id INT AUTO_INCREMENT PRIMARY KEY,
  amount DECIMAL(10,2) NOT NULL CHECK (amount > 0),
  category ENUM('Food','Shopping','Transportation','Education','Entertainment','Health','Bills','Others') NOT NULL,
  description VARCHAR(255) NOT NULL,
  expense_date DATE NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_expense_date (expense_date),
  INDEX idx_expense_category (category)
);

-- 2. Income
CREATE TABLE IF NOT EXISTS income (
  id INT AUTO_INCREMENT PRIMARY KEY,
  amount DECIMAL(10,2) NOT NULL CHECK (amount > 0),
  source ENUM('Salary','Pocket Money','Freelance','Scholarship','Gift','Others') NOT NULL,
  description VARCHAR(255),
  income_date DATE NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_income_date (income_date)
);

-- 3. Groups (`groups` is a reserved word in MySQL 8, so it needs backticks)
CREATE TABLE IF NOT EXISTS `groups` (
  id INT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 4. Group members (one group -> many members)
CREATE TABLE IF NOT EXISTS group_members (
  id INT AUTO_INCREMENT PRIMARY KEY,
  group_id INT NOT NULL,
  name VARCHAR(100) NOT NULL,
  UNIQUE KEY uq_group_member (group_id, name),
  FOREIGN KEY (group_id) REFERENCES `groups`(id) ON DELETE CASCADE
);

-- 5. Shared expenses (who paid, how it was split)
CREATE TABLE IF NOT EXISTS shared_expenses (
  id INT AUTO_INCREMENT PRIMARY KEY,
  group_id INT NOT NULL,
  paid_by INT NOT NULL,
  amount DECIMAL(10,2) NOT NULL CHECK (amount > 0),
  description VARCHAR(255) NOT NULL,
  split_type ENUM('equal','custom') NOT NULL DEFAULT 'equal',
  expense_date DATE NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_shared_group (group_id),
  FOREIGN KEY (group_id) REFERENCES `groups`(id) ON DELETE CASCADE,
  FOREIGN KEY (paid_by) REFERENCES group_members(id) ON DELETE CASCADE
);

-- 6. Expense participants (many-to-many: shared_expenses <-> group_members)
CREATE TABLE IF NOT EXISTS expense_participants (
  id INT AUTO_INCREMENT PRIMARY KEY,
  shared_expense_id INT NOT NULL,
  member_id INT NOT NULL,
  share_amount DECIMAL(10,2) NOT NULL CHECK (share_amount >= 0),
  UNIQUE KEY uq_participant (shared_expense_id, member_id),
  FOREIGN KEY (shared_expense_id) REFERENCES shared_expenses(id) ON DELETE CASCADE,
  FOREIGN KEY (member_id) REFERENCES group_members(id) ON DELETE CASCADE
);

-- 7. Settlements. request_token is unique, so a double-click or
-- repeated request cannot insert the same settlement twice.
CREATE TABLE IF NOT EXISTS settlements (
  id INT AUTO_INCREMENT PRIMARY KEY,
  group_id INT NOT NULL,
  from_member INT NOT NULL,
  to_member INT NOT NULL,
  amount DECIMAL(10,2) NOT NULL CHECK (amount > 0),
  settled_on DATE NOT NULL,
  request_token VARCHAR(64) NOT NULL UNIQUE,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  -- MySQL does not allow a CHECK on columns that have ON DELETE CASCADE, so
  -- 'payer and receiver must differ' is enforced in groupService.addSettlement instead.
  FOREIGN KEY (group_id) REFERENCES `groups`(id) ON DELETE CASCADE,
  FOREIGN KEY (from_member) REFERENCES group_members(id) ON DELETE CASCADE,
  FOREIGN KEY (to_member) REFERENCES group_members(id) ON DELETE CASCADE
);

-- 8. Budgets (category = 'Overall' means the whole-month budget)
CREATE TABLE IF NOT EXISTS budgets (
  id INT AUTO_INCREMENT PRIMARY KEY,
  budget_month CHAR(7) NOT NULL,            -- format YYYY-MM
  category VARCHAR(30) NOT NULL DEFAULT 'Overall',
  amount DECIMAL(10,2) NOT NULL CHECK (amount > 0),
  UNIQUE KEY uq_budget (budget_month, category)
);

-- 9. Savings goals
CREATE TABLE IF NOT EXISTS savings_goals (
  id INT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  target_amount DECIMAL(10,2) NOT NULL CHECK (target_amount > 0),
  deadline DATE,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 10. Savings contributions (one goal -> many contributions)
CREATE TABLE IF NOT EXISTS savings_contributions (
  id INT AUTO_INCREMENT PRIMARY KEY,
  goal_id INT NOT NULL,
  amount DECIMAL(10,2) NOT NULL CHECK (amount > 0),
  contributed_on DATE NOT NULL,
  FOREIGN KEY (goal_id) REFERENCES savings_goals(id) ON DELETE CASCADE
);

-- 11. No-spend challenges (unnecessary spending is computed from expenses by date range)
CREATE TABLE IF NOT EXISTS challenges (
  id INT AUTO_INCREMENT PRIMARY KEY,
  challenge_type ENUM('7-day','30-day') NOT NULL,
  start_date DATE NOT NULL,
  end_date DATE NOT NULL,
  status ENUM('active','completed','failed') NOT NULL DEFAULT 'active',
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
