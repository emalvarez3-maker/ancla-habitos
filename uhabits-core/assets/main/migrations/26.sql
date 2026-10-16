alter table Habits add column anchor text not null default '';
alter table Habits add column place text not null default '';
alter table Habits add column tiny_version text not null default '';
alter table Habits add column if_then text not null default '';
alter table Habits add column self_report real;
alter table Habits add column self_report_day integer;
