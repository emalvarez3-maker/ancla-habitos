create table Books (
    id integer primary key autoincrement,
    title text not null,
    author text not null default '',
    pages integer not null default 0,
    status integer not null default 0,
    current_page integer not null default 0,
    tags text not null default '',
    rating integer not null default 0,
    added_day integer not null,
    finished_day integer,
    file text not null default '',
    location text not null default ''
);
create table ReadingSessions (
    id integer primary key autoincrement,
    book integer not null references Books(id) on delete cascade,
    day integer not null,
    minutes integer not null,
    pages integer not null default 0
);
create index idx_reading_day on ReadingSessions (day);
