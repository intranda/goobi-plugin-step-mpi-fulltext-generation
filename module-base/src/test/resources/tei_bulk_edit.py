#!/usr/bin/env python3
"""
TEI-XML Massenbearbeitung – strada-projekt
Regeln:
  1) <row> mit erster cell "CENSUS–ID:" nur entfernen, wenn die zweite cell
     einen census-ref OHNE ID enthält (target endet auf "censusID=" ohne Wert)
  2) Alle <row> entfernen, deren erste cell "PHAIDRA-ID:" enthält
  3) Alle <row> vollständig entfernen mit erster cell "DIASKEUE:" UND
     zweiter cell "fol. ???, br. ???"
  4) In EDITORIAL-COMMENT-Tabellen:
       - "SOURCE, ID:"-Zeilen immer entfernen
       - "MANO ID – OBVERSE:"-Zeile nur entfernen, wenn im <ref> "Volume 01, folio 005r" steht
       - "MANO ID – REVERSE:"-Zeile nur entfernen, wenn im <ref> "Volume 01, folio 004r" steht
  5) Kontakttext (EN + DE) NUR in bestimmten Dateien (CONTACT_FILES) am Ende
     jeder Seite (getrennt durch <pb/>) einfügen, die mindestens eine <table>
     enthält – als <div><p>EN<lb/>DE</p></div> unmittelbar vor dem nächsten <pb/>
"""

from lxml import etree
import glob
import os
import shutil

TEI = 'http://www.tei-c.org/ns/1.0'
T = lambda name: f'{{{TEI}}}{name}'

CONTACT_TEXT_EN = (
"Note: Due to technical issues, not all data relating to Diaskeué is currently accessible. As part of the project, the coin descriptions from Caesar to Geta have been processed (Diaskeué volumes 2 to 6). Please get in touch if you have any questions regarding these coin descriptions: volker.heenes@icloud.com"
)
CONTACT_TEXT_DE = (
"Anmerkung: Aufgrund technischer Probleme sind nicht alle Daten zur Diaskeué zugänglich. Im Rahmen des Projekts wurden die Münzbeschreibungen von Caesar bis Geta bearbeitet (Diaskeué Bände 2 bis 6). Bitte melden Sie sich, falls Sie Fragen zu diesen Münzbeschreibungen haben: volker.heenes@icloud.com"
)

CENSUS_ID   = 'CENSUS–ID:'   # en-dash
PHAIDRA_ID  = 'PHAIDRA-ID:'
DIASKEUE    = 'DIASKEUE:'
EDITORIAL   = 'EDITORIAL COMMENT'

# Regel 5: Kontakttext nur in diesen Dateien am Seitenende einfügen
CONTACT_FILES = {
    '285341.xml', '285342.xml', '285343.xml', '285344.xml', '285346.xml',
}
ED_REMOVE   = {'SOURCE, ID:'}  # immer entfernen
# MANO-ID-Zeilen nur entfernen, wenn im <ref> der Platzhalter-Vorgabewert steht
MANO_REMOVE = {
    'MANO ID – OBVERSE:': 'Volume 01, folio 005r',
    'MANO ID – REVERSE:': 'Volume 01, folio 004r',
}


def cell_text(cell):
    return ''.join(cell.itertext()).strip()


def direct_cells(row):
    return [c for c in row if c.tag == T('cell')]


def census_ref_empty(cell):
    """True, wenn ein census-ref ohne ID vorliegt (target endet auf 'censusID=' und kein Text)."""
    for ref in cell.iter(T('ref')):
        target = ref.get('target', '')
        if 'census.bbaw.de' in target and 'censusID=' in target:
            id_part = target.split('censusID=', 1)[1].strip()
            text_part = ''.join(ref.itertext()).strip()
            return not id_part and not text_part
    return False


def build_contact_div():
    """<div><p>EN<lb/>DE</p></div> mit dem Kontakttext."""
    div = etree.Element(T('div'))
    p = etree.SubElement(div, T('p'))
    p.text = CONTACT_TEXT_EN
    lb = etree.SubElement(p, T('lb'))
    lb.tail = CONTACT_TEXT_DE
    return div


def insert_page_contacts(root):
    """Fügt am Ende jeder Seite (Bereich ab <pb/> bis zum nächsten <pb/>), die
    mindestens eine <table> enthält, den Kontakttext ein. Rückgabe: Anzahl."""
    n = 0
    for body in root.iter(T('body')):
        pbs = [c for c in body if c.tag == T('pb')]
        for i, pb in enumerate(pbs):
            next_pb = pbs[i + 1] if i + 1 < len(pbs) else None

            # Enthält diese Seite eine <table>?
            has_table = False
            el = pb.getnext()
            while el is not None and el is not next_pb:
                if el.tag == T('table') or el.find('.//' + T('table')) is not None:
                    has_table = True
                    break
                el = el.getnext()

            if has_table:
                div = build_contact_div()
                if next_pb is not None:
                    next_pb.addprevious(div)   # ans Seitenende (vor nächstem pb)
                else:
                    body.append(div)           # letzte Seite → ans Body-Ende
                n += 1
    return n


def process(filepath):
    parser = etree.XMLParser(remove_blank_text=False, resolve_entities=False)
    tree = etree.parse(filepath, parser)
    root = tree.getroot()

    counts = dict(census=0, phaidra=0, diaskeue=0, editorial=0, contact=0)

    # ── Regeln 1, 2, 3 (einmaliger Durchlauf über alle rows) ─────────────────
    for row in list(root.iter(T('row'))):
        cells = direct_cells(row)
        if not cells:
            continue

        first = cell_text(cells[0])

        # Regel 1 – CENSUS–ID: nur entfernen, wenn keine Census-ID vorhanden ist
        if first == CENSUS_ID:
            if len(cells) > 1 and census_ref_empty(cells[1]):
                row.getparent().remove(row)
                counts['census'] += 1
            continue

        # Regel 2 – PHAIDRA-ID
        if first == PHAIDRA_ID:
            row.getparent().remove(row)
            counts['phaidra'] += 1
            continue

        # Regel 3 – DIASKEUE: mit Platzhalter "fol. ???, br. ???" → Zeile entfernen
        if first == DIASKEUE and len(cells) > 1:
            second_text = cell_text(cells[1])
            if 'fol. ???' in second_text and 'br. ???' in second_text:
                row.getparent().remove(row)
                counts['diaskeue'] += 1
            continue

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
                    if not cells:
                        continue
                    first = cell_text(cells[0])
                    # SOURCE, ID: – immer entfernen
                    if first in ED_REMOVE:
                        sib.remove(row)
                        counts['editorial'] += 1
                    # MANO ID – OBVERSE/REVERSE: nur bei Platzhalter-Vorgabewert im <ref>
                    elif first in MANO_REMOVE and len(cells) > 1:
                        if MANO_REMOVE[first] in cell_text(cells[1]):
                            sib.remove(row)
                            counts['editorial'] += 1
                break

    # ── Regel 5 – Kontakttext am Seitenende (nur bestimmte Dateien) ──────────
    if os.path.basename(filepath) in CONTACT_FILES:
        counts['contact'] = insert_page_contacts(root)

    return tree, counts


def main():
    xml_dir = '/home/robert/git/goobi-plugin-step-mpi-fulltext-generation/module-base/src/test/resources/xml'
    files = sorted(glob.glob(os.path.join(xml_dir, '*.xml'), recursive=True))

    backup_root = os.path.join(os.path.dirname(xml_dir), 'xml_backup')
    os.makedirs(backup_root, exist_ok=True)

    total = dict(census=0, phaidra=0, diaskeue=0, editorial=0, contact=0)

    for filepath in files:
        # Backup anlegen
        rel = os.path.relpath(filepath, xml_dir)
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
                f'diaskeue={counts["diaskeue"]}  '
                f'editorial={counts["editorial"]}  '
                f'contact={counts["contact"]}'
            )

    print()
    print('─' * 60)
    print(
        f'GESAMT:  '
        f'census={total["census"]}  '
        f'phaidra={total["phaidra"]}  '
        f'diaskeue={total["diaskeue"]}  '
        f'editorial={total["editorial"]}  '
        f'contact={total["contact"]}'
    )
    print(f'Backup:  {backup_root}')


if __name__ == '__main__':
    main()
