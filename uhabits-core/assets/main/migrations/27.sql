create table DayTasks (
    id integer primary key autoincrement,
    title text not null,
    notes text not null default '',
    day integer not null,
    minute integer,
    reminder integer not null default 0,
    done integer not null default 0
);
create index idx_daytasks_day on DayTasks (day);
