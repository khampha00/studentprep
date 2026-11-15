import { useEffect, useId, useMemo, useRef, useState, type ReactNode } from 'react';
import {
  ArrowDown,
  ArrowUp,
  ArrowUpDown,
  Filter,
  RotateCcw,
  Search,
  X,
} from 'lucide-react';
import { Button } from './button';

export type DataTableSortDirection = 'asc' | 'desc';

export interface DataTableColumn<T> {
  key: string;
  header: string;
  align?: 'left' | 'center' | 'right';
  headerClassName?: string;
  cellClassName?: string;
  sortable?: boolean;
  sortType?: 'text' | 'number' | 'date';
  sortValue?: (row: T) => string | number | null | undefined;
  searchValue?: (row: T) => string | number | null | undefined;
  hideLabelOnMobile?: boolean;
  render: (row: T, index: number) => ReactNode;
}

export interface DataTableFilterOption {
  value: string;
  label: string;
}

export interface DataTableFilterConfig<T> {
  key: string;
  label: string;
  allLabel?: string;
  options: DataTableFilterOption[];
  predicate?: (row: T, selectedValue: string) => boolean;
}

export interface DataTableServerQuery {
  page: number;
  pageSize: number;
  search: string;
  filters: Record<string, string>;
  sortKey: string | null;
  sortDirection: DataTableSortDirection;
}

export interface DataTableProps<T> {
  caption: string;
  data: T[];
  columns: DataTableColumn<T>[];
  getRowKey: (row: T, index: number) => string;
  mode?: 'client' | 'server';
  loading?: boolean;
  loadingMessage?: string;
  emptyMessage?: string;
  noMatchesMessage?: string;
  itemLabel?: [string, string];
  showSequence?: boolean;
  sequenceHeader?: string;
  searchable?: boolean;
  searchPlaceholder?: string;
  searchKeys?: ((row: T) => string | number | null | undefined)[];
  filters?: DataTableFilterConfig<T>[];
  defaultSort?: { key: string; direction: DataTableSortDirection };
  pageSizeOptions?: number[];
  defaultPageSize?: number;
  maxHeightClassName?: string;
  responsiveCards?: boolean;
  toolbarEnd?: ReactNode;
  className?: string;
  page?: number;
  pageSize?: number;
  totalElements?: number;
  totalPages?: number;
  search?: string;
  filterValues?: Record<string, string>;
  sortKey?: string | null;
  sortDirection?: DataTableSortDirection;
  onQueryChange?: (query: DataTableServerQuery) => void;
}

const textCollator = new Intl.Collator(undefined, { numeric: true, sensitivity: 'base' });
const EMPTY_FILTERS: Record<string, string> = {};

function toComparableNumber(raw: string | number | null | undefined, sortType: 'number' | 'date'): number {
  if (typeof raw === 'number') {
    return Number.isFinite(raw) ? raw : 0;
  }
  if (raw === null || raw === undefined || raw === '') {
    return 0;
  }
  if (sortType === 'date') {
    const parsed = Date.parse(String(raw));
    return Number.isFinite(parsed) ? parsed : 0;
  }
  const num = Number(raw);
  return Number.isFinite(num) ? num : 0;
}

function buildPageRange(currentPage: number, totalPages: number): (number | 'ellipsis')[] {
  if (totalPages <= 7) {
    return Array.from({ length: totalPages }, (_, i) => i);
  }
  const pages = new Set<number>([0, totalPages - 1, currentPage]);
  if (currentPage - 1 >= 0) pages.add(currentPage - 1);
  if (currentPage + 1 < totalPages) pages.add(currentPage + 1);

  const sorted = Array.from(pages).sort((a, b) => a - b);
  const result: (number | 'ellipsis')[] = [];
  for (let i = 0; i < sorted.length; i++) {
    if (i > 0 && sorted[i] - sorted[i - 1] > 1) {
      result.push('ellipsis');
    }
    result.push(sorted[i]);
  }
  return result;
}

function alignClass(align?: 'left' | 'center' | 'right'): string {
  if (align === 'right') return 'text-right';
  if (align === 'center') return 'text-center';
  return 'text-left';
}

function SortIcon({ direction }: { direction: DataTableSortDirection | null }) {
  if (direction === 'asc') {
    return <ArrowUp aria-hidden="true" className="w-3.5 h-3.5 text-[#008751] shrink-0 sp-sort-icon-asc" />;
  }
  if (direction === 'desc') {
    return <ArrowDown aria-hidden="true" className="w-3.5 h-3.5 text-[#008751] shrink-0 sp-sort-icon-desc" />;
  }
  return <ArrowUpDown aria-hidden="true" className="w-3.5 h-3.5 text-slate-400 group-hover:text-slate-600 shrink-0" />;
}

/**
 * Shared responsive, accessible, DRY data table template supporting sequence numbering,
 * search, faceted filters, column sorting, and pagination in client or server mode.
 */
export function DataTable<T>({
  caption,
  data,
  columns,
  getRowKey,
  mode = 'client',
  loading = false,
  loadingMessage = 'Loading records...',
  emptyMessage = 'No records available.',
  noMatchesMessage = 'No matching records match your current search or filters.',
  itemLabel = ['record', 'records'],
  showSequence = true,
  sequenceHeader = '#',
  searchable = true,
  searchPlaceholder = 'Search...',
  searchKeys,
  filters = [],
  defaultSort,
  pageSizeOptions = [10, 20, 50, 100],
  defaultPageSize = 20,
  maxHeightClassName = 'max-h-[65vh]',
  responsiveCards = true,
  toolbarEnd,
  className = '',
  page: controlledPage,
  pageSize: controlledPageSize,
  totalElements: controlledTotalElements,
  totalPages: controlledTotalPages,
  search: controlledSearch,
  filterValues: controlledFilters,
  sortKey: controlledSortKey,
  sortDirection: controlledSortDirection,
  onQueryChange,
}: DataTableProps<T>) {
  const captionId = useId();
  const searchInputId = useId();
  const pageSizeSelectId = useId();

  const [clientPage, setClientPage] = useState(0);
  const [clientPageSize, setClientPageSize] = useState(defaultPageSize);
  const [clientSearch, setClientSearch] = useState('');
  const [clientFilters, setClientFilters] = useState<Record<string, string>>({});
  const [clientSortKey, setClientSortKey] = useState<string | null>(defaultSort?.key ?? null);
  const [clientSortDirection, setClientSortDirection] = useState<DataTableSortDirection>(
    defaultSort?.direction ?? 'asc'
  );
  const [sortAnnouncement, setSortAnnouncement] = useState('');

  // Maintain responsive input state while debouncing server-side searches.
  const [serverSearchDraft, setServerSearchDraft] = useState(controlledSearch ?? '');
  const debounceTimerRef = useRef<number | null>(null);

  useEffect(() => {
    return () => {
      if (debounceTimerRef.current !== null) {
        window.clearTimeout(debounceTimerRef.current);
      }
    };
  }, []);

  const currentSearchDisplay = mode === 'server' ? serverSearchDraft : clientSearch;
  const activeFilterValues = mode === 'server' ? (controlledFilters ?? EMPTY_FILTERS) : clientFilters;
  const activeSortKey = mode === 'server' ? (controlledSortKey ?? null) : clientSortKey;
  const activeSortDirection = mode === 'server'
    ? (controlledSortDirection ?? 'asc')
    : clientSortDirection;
  const activePageSize = mode === 'server' ? (controlledPageSize ?? defaultPageSize) : clientPageSize;
  const requestedPage = mode === 'server' ? (controlledPage ?? 0) : clientPage;

  const hasActiveFilters = useMemo(() => {
    if (currentSearchDisplay.trim().length > 0) {
      return true;
    }
    return Object.values(activeFilterValues).some((v) => v && v !== 'ALL');
  }, [currentSearchDisplay, activeFilterValues]);

  const emitServerQuery = (patch: Partial<DataTableServerQuery>) => {
    if (mode !== 'server' || !onQueryChange) {
      return;
    }
    onQueryChange({
      page: patch.page ?? requestedPage,
      pageSize: patch.pageSize ?? activePageSize,
      search: patch.search ?? (controlledSearch ?? serverSearchDraft),
      filters: patch.filters ?? activeFilterValues,
      sortKey: patch.sortKey !== undefined ? patch.sortKey : activeSortKey,
      sortDirection: patch.sortDirection ?? activeSortDirection,
    });
  };

  const clientProcessedRows = useMemo(() => {
    if (mode === 'server') {
      return data;
    }

    const extractors =
      searchKeys && searchKeys.length > 0
        ? searchKeys
        : columns.map((col) => col.searchValue).filter((fn): fn is NonNullable<typeof fn> => Boolean(fn));

    const query = clientSearch.trim().toLowerCase();

    const filtered = data.filter((row) => {
      if (query && extractors.length > 0) {
        const matched = extractors.some((fn) => {
          const val = fn(row);
          return val !== null && val !== undefined && String(val).toLowerCase().includes(query);
        });
        if (!matched) {
          return false;
        }
      }

      for (const filter of filters) {
        const selected = clientFilters[filter.key];
        if (selected && selected !== 'ALL' && filter.predicate) {
          if (!filter.predicate(row, selected)) {
            return false;
          }
        }
      }
      return true;
    });

    if (!clientSortKey) {
      return filtered;
    }

    const sortCol = columns.find((c) => c.key === clientSortKey);
    if (!sortCol || !sortCol.sortable) {
      return filtered;
    }

    const valueGetter =
      sortCol.sortValue ??
      sortCol.searchValue ??
      ((row: T) => (row as Record<string, unknown>)[sortCol.key] as string | number | null | undefined);

    const factor = clientSortDirection === 'asc' ? 1 : -1;
    const sortType = sortCol.sortType ?? 'text';

    return [...filtered].sort((a, b) => {
      const av = valueGetter(a);
      const bv = valueGetter(b);
      if (sortType === 'number' || sortType === 'date') {
        return factor * (toComparableNumber(av, sortType) - toComparableNumber(bv, sortType));
      }
      return factor * textCollator.compare(String(av ?? ''), String(bv ?? ''));
    });
  }, [
    mode,
    data,
    searchKeys,
    columns,
    clientSearch,
    filters,
    clientFilters,
    clientSortKey,
    clientSortDirection,
  ]);

  const totalItems = mode === 'server' ? (controlledTotalElements ?? data.length) : clientProcessedRows.length;
  const computedTotalPages =
    mode === 'server'
      ? Math.max(1, controlledTotalPages ?? Math.ceil(totalItems / Math.max(1, activePageSize)))
      : Math.max(1, Math.ceil(totalItems / Math.max(1, activePageSize)));

  const safePage = Math.min(Math.max(0, requestedPage), computedTotalPages - 1);

  const visibleRows = useMemo(() => {
    if (mode === 'server') {
      return data;
    }
    const start = safePage * activePageSize;
    return clientProcessedRows.slice(start, start + activePageSize);
  }, [mode, data, clientProcessedRows, safePage, activePageSize]);

  const totalColSpan = columns.length + (showSequence ? 1 : 0);
  const rangeStart = totalItems === 0 ? 0 : safePage * activePageSize + 1;
  const rangeEnd = totalItems === 0 ? 0 : Math.min(totalItems, safePage * activePageSize + visibleRows.length);
  const unitLabel = totalItems === 1 ? itemLabel[0] : itemLabel[1];
  const pageButtons = buildPageRange(safePage, computedTotalPages);

  const handleSearchChange = (value: string) => {
    if (mode === 'server') {
      setServerSearchDraft(value);
      if (debounceTimerRef.current !== null) {
        window.clearTimeout(debounceTimerRef.current);
      }
      debounceTimerRef.current = window.setTimeout(() => {
        emitServerQuery({ search: value, page: 0 });
      }, 250);
    } else {
      setClientSearch(value);
      setClientPage(0);
    }
  };

  const handleClearSearch = () => {
    if (mode === 'server') {
      if (debounceTimerRef.current !== null) {
        window.clearTimeout(debounceTimerRef.current);
      }
      setServerSearchDraft('');
      emitServerQuery({ search: '', page: 0 });
    } else {
      setClientSearch('');
      setClientPage(0);
    }
  };

  const handleFilterChange = (key: string, value: string) => {
    const nextFilters = { ...activeFilterValues, [key]: value };
    if (mode === 'server') {
      emitServerQuery({ filters: nextFilters, page: 0 });
    } else {
      setClientFilters(nextFilters);
      setClientPage(0);
    }
  };

  const handleResetAll = () => {
    if (mode === 'server') {
      if (debounceTimerRef.current !== null) {
        window.clearTimeout(debounceTimerRef.current);
      }
      setServerSearchDraft('');
      emitServerQuery({ search: '', filters: {}, page: 0 });
    } else {
      setClientSearch('');
      setClientFilters({});
      setClientPage(0);
    }
  };

  const handleSort = (column: DataTableColumn<T>) => {
    if (!column.sortable) {
      return;
    }
    const nextDirection: DataTableSortDirection =
      activeSortKey === column.key && activeSortDirection === 'asc' ? 'desc' : 'asc';

    const dirWord = nextDirection === 'asc' ? 'ascending' : 'descending';
    setSortAnnouncement(`Sorted by ${column.header}, ${dirWord}, ${totalItems} ${unitLabel}`);

    if (mode === 'server') {
      emitServerQuery({ sortKey: column.key, sortDirection: nextDirection, page: 0 });
    } else {
      setClientSortKey(column.key);
      setClientSortDirection(nextDirection);
      setClientPage(0);
    }
  };

  const handlePageSizeChange = (nextSize: number) => {
    if (mode === 'server') {
      emitServerQuery({ pageSize: nextSize, page: 0 });
    } else {
      setClientPageSize(nextSize);
      setClientPage(0);
    }
  };

  const handlePageChange = (nextPage: number) => {
    const bounded = Math.min(Math.max(0, nextPage), computedTotalPages - 1);
    if (mode === 'server') {
      emitServerQuery({ page: bounded });
    } else {
      setClientPage(bounded);
    }
  };

  return (
    <div
      className={`border border-slate-200 rounded-xl bg-zinc-50 shadow-xs overflow-hidden font-sans text-slate-900 ${className}`}
    >
      {/* Toolbar: Search, Filters, Summary */}
      {(searchable || filters.length > 0 || toolbarEnd || totalItems > 0) && (
        <div className="flex flex-wrap items-center justify-between gap-3 px-4 py-3 bg-slate-50 border-b border-slate-200">
          <div className="flex flex-wrap items-center gap-2.5 flex-1 min-w-[220px]">
            {searchable && (
              <div className="relative w-full sm:w-64">
                <label htmlFor={searchInputId} className="sr-only">
                  {searchPlaceholder}
                </label>
                <Search
                  aria-hidden="true"
                  className="w-4 h-4 text-slate-400 pointer-events-none absolute left-3 top-1/2 -translate-y-1/2"
                />
                <input
                  id={searchInputId}
                  type="search"
                  value={currentSearchDisplay}
                  onChange={(e) => handleSearchChange(e.target.value)}
                  placeholder={searchPlaceholder}
                  className="w-full h-9 pl-9 pr-8 rounded-lg border border-slate-300 bg-white text-xs text-slate-900 placeholder:text-slate-400 focus:outline-none focus:ring-2 focus:ring-[#008751]/40 focus:border-[#008751]"
                />
                {currentSearchDisplay.length > 0 && (
                  <button
                    type="button"
                    onClick={handleClearSearch}
                    aria-label="Clear search"
                    className="absolute right-2 top-1/2 -translate-y-1/2 p-1 rounded text-slate-400 hover:text-slate-700 cursor-pointer"
                  >
                    <X className="w-3.5 h-3.5" />
                  </button>
                )}
              </div>
            )}

            {filters.map((filter) => {
              const selectedValue = activeFilterValues[filter.key] ?? 'ALL';
              const selectId = `${captionId}-filter-${filter.key}`;
              return (
                <div key={filter.key} className="flex items-center gap-1.5">
                  <label
                    htmlFor={selectId}
                    className="inline-flex items-center gap-1 text-xs font-medium text-slate-600"
                  >
                    <Filter aria-hidden="true" className="w-3.5 h-3.5 text-slate-400" />
                    <span>{filter.label}:</span>
                  </label>
                  <select
                    id={selectId}
                    value={selectedValue}
                    onChange={(e) => handleFilterChange(filter.key, e.target.value)}
                    className="h-9 rounded-lg border border-slate-300 bg-white px-2.5 text-xs font-medium text-slate-800 focus:outline-none focus:ring-2 focus:ring-[#008751]/40 focus:border-[#008751] cursor-pointer"
                  >
                    <option value="ALL">{filter.allLabel ?? 'All'}</option>
                    {filter.options.map((opt) => (
                      <option key={opt.value} value={opt.value}>
                        {opt.label}
                      </option>
                    ))}
                  </select>
                </div>
              );
            })}

            {hasActiveFilters && (
              <Button
                type="button"
                size="sm"
                variant="ghost"
                onClick={handleResetAll}
                className="h-9 px-2.5 text-xs text-slate-600 hover:text-slate-900"
              >
                <RotateCcw className="w-3.5 h-3.5 mr-1" />
                Reset
              </Button>
            )}
          </div>

          <div className="flex items-center gap-3 text-xs text-slate-600">
            {totalItems > 0 && (
              <span className="font-medium tabular-nums">
                Showing {rangeStart}–{rangeEnd} of {totalItems}
              </span>
            )}
            {toolbarEnd}
          </div>
        </div>
      )}

      {/* Single live region for sort announcements per sortable-table guidance */}
      <div role="status" aria-live="polite" className="sr-only">
        {sortAnnouncement}
      </div>

      {/* Scrollable accessible region with sticky header */}
      <div
        tabIndex={0}
        role="region"
        aria-labelledby={captionId}
        className={`sp-table-region ${maxHeightClassName} bg-white focus:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-[#008751]/50`}
      >
        <table
          className={`sp-data-table text-sm text-slate-700 ${
            responsiveCards ? 'sp-data-table-responsive' : ''
          }`}
        >
          <caption id={captionId} className="sr-only">
            {caption}
          </caption>
          <thead>
            <tr className="bg-slate-100/95 text-[11px] font-bold uppercase tracking-wider text-slate-700">
              {showSequence && (
                <th
                  scope="col"
                  className="px-3 py-3 text-center w-12 border-b border-slate-200 bg-slate-100/95 whitespace-nowrap"
                >
                  {sequenceHeader}
                </th>
              )}
              {columns.map((col) => {
                const isSorted = activeSortKey === col.key;
                const ariaSort = isSorted
                  ? activeSortDirection === 'asc'
                    ? 'ascending'
                    : 'descending'
                  : undefined;

                return (
                  <th
                    key={col.key}
                    scope="col"
                    aria-sort={ariaSort}
                    data-type={col.sortType ?? 'text'}
                    className={`border-b border-slate-200 bg-slate-100/95 whitespace-nowrap ${
                      col.sortable ? 'p-0' : 'px-4 py-3'
                    } ${alignClass(col.align)} ${col.headerClassName ?? ''}`}
                  >
                    {col.sortable ? (
                      <button
                        type="button"
                        onClick={() => handleSort(col)}
                        className={`group w-full min-h-10 px-4 py-3 inline-flex items-center gap-1.5 font-bold uppercase tracking-wider text-[11px] text-slate-700 hover:bg-slate-200/60 hover:text-slate-900 focus:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-[#008751] cursor-pointer transition-colors ${
                          col.align === 'right'
                            ? 'justify-end'
                            : col.align === 'center'
                            ? 'justify-center'
                            : 'justify-start'
                        }`}
                      >
                        <span>{col.header}</span>
                        <SortIcon direction={isSorted ? activeSortDirection : null} />
                      </button>
                    ) : (
                      col.header
                    )}
                  </th>
                );
              })}
            </tr>
          </thead>

          <tbody className="divide-y divide-slate-100">
            {loading && visibleRows.length === 0 ? (
              <tr>
                <td colSpan={totalColSpan} className="px-4 py-10 text-center text-sm text-slate-500">
                  {loadingMessage}
                </td>
              </tr>
            ) : visibleRows.length === 0 ? (
              <tr>
                <td colSpan={totalColSpan} className="px-4 py-10 text-center text-sm text-slate-500">
                  <div className="space-y-2">
                    <p>{hasActiveFilters ? noMatchesMessage : emptyMessage}</p>
                    {hasActiveFilters && (
                      <Button
                        type="button"
                        size="sm"
                        variant="outline"
                        onClick={handleResetAll}
                        className="text-xs"
                      >
                        Clear search & filters
                      </Button>
                    )}
                  </div>
                </td>
              </tr>
            ) : (
              visibleRows.map((row, idx) => {
                const sequenceNumber = safePage * activePageSize + idx + 1;
                return (
                  <tr
                    key={getRowKey(row, idx)}
                    className="hover:bg-emerald-50/35 transition-colors bg-white"
                  >
                    {showSequence && (
                      <td
                        data-label={sequenceHeader}
                        className="px-3 py-3 text-center font-mono text-xs font-semibold text-slate-500 tabular-nums border-b border-slate-100"
                      >
                        {sequenceNumber}
                      </td>
                    )}
                    {columns.map((col) => (
                      <td
                        key={col.key}
                        data-label={col.hideLabelOnMobile ? undefined : col.header}
                        className={`px-4 py-3 text-sm border-b border-slate-100 ${alignClass(col.align)} ${
                          col.cellClassName ?? ''
                        }`}
                      >
                        {col.render(row, idx)}
                      </td>
                    ))}
                  </tr>
                );
              })
            )}
          </tbody>
        </table>
      </div>

      {/* Pagination Footer */}
      {totalItems > 0 && (
        <div className="flex flex-wrap items-center justify-between gap-3 px-4 py-3 border-t border-slate-200 bg-slate-50 text-xs text-slate-700">
          <div className="flex flex-wrap items-center gap-3">
            <span className="font-medium tabular-nums">
              Page {safePage + 1} of {computedTotalPages} ({totalItems} {unitLabel})
            </span>
            <div className="flex items-center gap-1.5">
              <label htmlFor={pageSizeSelectId} className="text-slate-600">
                Rows:
              </label>
              <select
                id={pageSizeSelectId}
                value={activePageSize}
                disabled={loading}
                onChange={(e) => handlePageSizeChange(Number(e.target.value))}
                className="h-7 rounded-md border border-slate-300 bg-white px-2 text-xs font-medium text-slate-800 focus:outline-none focus:ring-2 focus:ring-[#008751]/40 cursor-pointer"
              >
                {pageSizeOptions.map((sizeOpt) => (
                  <option key={sizeOpt} value={sizeOpt}>
                    {sizeOpt}
                  </option>
                ))}
              </select>
            </div>
          </div>

          <div className="flex flex-wrap items-center gap-1.5">
            <Button
              type="button"
              size="sm"
              variant="outline"
              disabled={safePage <= 0 || loading}
              onClick={() => handlePageChange(safePage - 1)}
              className="h-8 px-3 text-xs"
            >
              Previous
            </Button>

            {pageButtons.map((entry, idx) =>
              entry === 'ellipsis' ? (
                <span key={`ellipsis-${idx}`} className="px-1.5 text-slate-400 select-none">
                  ...
                </span>
              ) : (
                <button
                  key={entry}
                  type="button"
                  disabled={loading}
                  aria-current={entry === safePage ? 'page' : undefined}
                  aria-label={`Page ${entry + 1}`}
                  onClick={() => handlePageChange(entry)}
                  className={`h-8 min-w-8 px-2 rounded-md text-xs font-semibold transition-colors cursor-pointer ${
                    entry === safePage
                      ? 'bg-[#008751] text-white shadow-xs'
                      : 'bg-white border border-slate-200 text-slate-700 hover:bg-slate-100'
                  }`}
                >
                  {entry + 1}
                </button>
              )
            )}

            <Button
              type="button"
              size="sm"
              variant="outline"
              disabled={safePage + 1 >= computedTotalPages || loading}
              onClick={() => handlePageChange(safePage + 1)}
              className="h-8 px-3 text-xs"
            >
              Next
            </Button>
          </div>
        </div>
      )}
    </div>
  );
}
