#!/usr/bin/env python3
"""
TEI-XML Massenbearbeitung – strada-projekt
Regeln:
  1) Alle <row> entfernen, deren erste cell "CENSUS–ID:" enthält
  2) Alle <row> entfernen, deren erste cell "PHAIDRA-ID:" enthält
  3) Alle <row> entfernen mit erster cell "DIASKEUE:" UND zweiter cell "fol. ???, br. ???"
  4) In EDITORIAL-COMMENT-Tabellen: Zeilen "MANO ID – OBVERSE:", "MANO ID – REVERSE:", "SOURCE, ID:" entfernen
  5) DIASKEUE:-Zeilen mit leerer zweiter Zelle → Kontakttext einfügen
"""

from lxml import etree
import glob
import os
import shutil

TEI = 'http://www.tei-c.org/ns/1.0'
T = lambda name: f'{{{TEI}}}{name}'

CONTACT_TEXT = (
"Note: Due to technical issues, not all data on the Diaskeué is accessible. Within the framework of the project, the coin descriptions from Caesar to Geta have been processed (Diaskeué vols. 2 to 6). Please get in touch if you have any questions regarding these coin descriptions: volker.heenes@icloud.com"
)

CENSUS_ID   = 'CENSUS–ID:'   # en-dash
PHAIDRA_ID  = 'PHAIDRA-ID:'
DIASKEUE    = 'DIASKEUE:'
EDITORIAL   = 'EDITORIAL COMMENT'
ED_REMOVE   = {'MANO ID – OBVERSE:', 'MANO ID – REVERSE:', 'SOURCE, ID:'}


def cell_text(cell):
    return ''.join(cell.itertext()).strip()


def direct_cells(row):
    return [c for c in row if c.tag == T('cell')]


def process(filepath):
    parser = etree.XMLParser(remove_blank_text=False, resolve_entities=False)
    tree = etree.parse(filepath, parser)
    root = tree.getroot()

    counts = dict(census=0, phaidra=0, diaskeue_del=0, editorial=0, diaskeue_fill=0)

    # ── Regeln 1, 2, 3, 5 (einmaliger Durchlauf über alle rows) ──────────────
    for row in list(root.iter(T('row'))):
        cells = direct_cells(row)
        if not cells:
            continue

        first = cell_text(cells[0])

        # Regel 1 – CENSUS–ID
        if first == CENSUS_ID:
            row.getparent().remove(row)
            counts['census'] += 1
            continue

        # Regel 2 – PHAIDRA-ID
        if first == PHAIDRA_ID:
            row.getparent().remove(row)
            counts['phaidra'] += 1
            continue

        if first == DIASKEUE and len(cells) > 1:
            second_text = cell_text(cells[1])

            # Regel 3 – DIASKEUE mit Platzhalter fol. ???, br. ??? → Zelle leeren
            if 'fol. ???' in second_text and 'br. ???' in second_text:
                cell2 = cells[1]
                for child in list(cell2):
                    cell2.remove(child)
                cell2.text = None
                counts['diaskeue_del'] += 1
                second_text = ''  # fällt durch zu Regel 5

            # Regel 5 – Kontakttext immer einfügen (bei vorhandenem Inhalt neue Zeile)
            cell2 = cells[1]
            if not second_text:
                for child in list(cell2):
                    cell2.remove(child)
                cell2.text = CONTACT_TEXT
            else:
                children = list(cell2)
                lb = etree.SubElement(cell2, T('lb'))
                lb.tail = CONTACT_TEXT
            counts['diaskeue_fill'] += 1

    # ── Regel 4 – EDITORIAL COMMENT Tabellen ─────────────────────────────────
    for label in root.iter(T('label')):
        if ''.join(label.itertext()).strip() != EDITORIAL:
            continue
        parent = label.getparent()
        if parent is None:
            continue
        siblings = list(parent)
        try:
            idx = siblings.index(label)
        except ValueError:
            continue
        # Erste <table> nach dem Label
        for sib in siblings[idx + 1:]:
            if sib.tag == T('table'):
                for row in list(sib):
                    if row.tag != T('row'):
                        continue
                    cells = direct_cells(row)
                    if cells and cell_text(cells[0]) in ED_REMOVE:
                        sib.remove(row)
                        counts['editorial'] += 1
                break

    return tree, counts


def main():
    files = sorted(glob.glob('/home/robert/strada-projekt/tei-convert/xml/*.xml', recursive=True))

    backup_root = '/home/robert/strada-projekt/tei-convert/xml_backup'
    os.makedirs(backup_root, exist_ok=True)

    total = dict(census=0, phaidra=0, diaskeue_del=0, editorial=0, diaskeue_fill=0)

    for filepath in files:
        # Backup anlegen
        rel = os.path.relpath(filepath, '/home/robert/strada-projekt/xml')
        backup_path = os.path.join(backup_root, rel)
        os.makedirs(os.path.dirname(backup_path), exist_ok=True)
        shutil.copy2(filepath, backup_path)

        tree, counts = process(filepath)

        tree.write(filepath, encoding='UTF-8', xml_declaration=True, pretty_print=False)

        for k, v in counts.items():
            total[k] += v

        fname = os.path.basename(filepath)
        if any(v > 0 for v in counts.values()):
            print(
                f'{fname}: '
                f'census={counts["census"]}  '
                f'phaidra={counts["phaidra"]}  '
                f'diaskeue_del={counts["diaskeue_del"]}  '
                f'editorial={counts["editorial"]}  '
                f'diaskeue_fill={counts["diaskeue_fill"]}'
            )

    print()
    print('─' * 60)
    print(
        f'GESAMT:  '
        f'census={total["census"]}  '
        f'phaidra={total["phaidra"]}  '
        f'diaskeue_del={total["diaskeue_del"]}  '
        f'editorial={total["editorial"]}  '
        f'diaskeue_fill={total["diaskeue_fill"]}'
    )
    print(f'Backup:  {backup_root}')


if __name__ == '__main__':
    main()
